package org.vorpal.blade.framework.v3.configuration.auth;

import org.vorpal.blade.framework.v3.net.Remotes;
import java.net.URI;
import java.net.http.HttpRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.CallStep;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.diagnostics.HttpSteps;
import org.vorpal.blade.framework.v3.diagnostics.TraceLog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.token.AccessToken;

/// Shared machinery for OAuth 2.0 grants that produce a bearer access
/// token from a token endpoint. Delegates the RFC-specific construction
/// of the `TokenRequest` to subclasses, then performs the request via
/// the Nimbus OAuth 2.0 SDK and caches the resulting access token.
///
/// Thread-safety: [#currentToken] is synchronized so concurrent calls
/// with the same Authentication instance don't fire parallel token
/// fetches during refresh.
///
/// A fetch made for a traced call joins its trace, both HTTP messages, with
/// the secrets in the request and the tokens in the answer masked. A token
/// served from the cache makes no request, so a call shows a fetch only when
/// it was the one that needed a new token.
///
/// Failure mode: if the token fetch fails, [#currentToken] returns null
/// and [#applyTo] leaves the Authorization header unset. The upstream
/// server typically replies 401, the RestConnector logs that, and the
/// pipeline continues.
public abstract class AbstractOAuth2Authentication extends Authentication {
	private static final long serialVersionUID = 1L;

	protected String tokenUrl;
	protected String scope;
	protected Integer refreshSkewSeconds = 60;

	@JsonIgnore
	private transient volatile String accessToken;
	@JsonIgnore
	private transient volatile long expiresAtMs;

	@JsonPropertyDescription("OAuth 2.0 token endpoint URL; supports ${var}")
	public String getTokenUrl() {
		return tokenUrl;
	}

	public void setTokenUrl(String tokenUrl) {
		this.tokenUrl = tokenUrl;
	}

	@JsonPropertyDescription("Optional space-separated scope list; supports ${var}")
	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	@JsonPropertyDescription("Seconds of clock skew to subtract from token expiry before refreshing; default 60")
	public Integer getRefreshSkewSeconds() {
		return refreshSkewSeconds;
	}

	public void setRefreshSkewSeconds(Integer refreshSkewSeconds) {
		this.refreshSkewSeconds = refreshSkewSeconds;
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		String token = currentToken(ctx);
		if (token != null) {
			reqBuilder.header("Authorization", "Bearer " + token);
		}
	}

	/// Returns a valid access token (fetching or refreshing as needed),
	/// or null if the token endpoint call failed.
	protected synchronized String currentToken(Context ctx) {
		long now = System.currentTimeMillis();
		long skewMs = ((refreshSkewSeconds != null) ? refreshSkewSeconds : 60) * 1000L;
		if (accessToken != null && now + skewMs < expiresAtMs) {
			return accessToken;
		}
		Logger sipLogger = SettingsManager.getSipLogger();
		long started = System.nanoTime();
		String remote = null;
		String traced = Callflow.tracedSessionId(ctx != null ? ctx.getRequest() : null);
		boolean sent = false;
		try {
			TokenRequest req = buildTokenRequest(ctx);
			remote = Remotes.hostOf(String.valueOf(req.getEndpointURI()));
			com.nimbusds.oauth2.sdk.http.HTTPRequest httpRequest = req.toHTTPRequest();
			javax.net.ssl.SSLContext ssl = getSslContext();
			if (ssl != null) {
				httpRequest.setSSLSocketFactory(ssl.getSocketFactory());
			}
			if (traced != null) {
				trace(traced, CallStep.OUT, HttpSteps.REQUEST,
						httpRequest.getMethod() + " " + httpRequest.getURL().getPath(), requestText(httpRequest));
			}
			sent = true;
			com.nimbusds.oauth2.sdk.http.HTTPResponse httpResponse = httpRequest.send();
			if (traced != null) {
				trace(traced, CallStep.IN, HttpSteps.RESPONSE, String.valueOf(httpResponse.getStatusCode()),
						responseText(httpResponse));
			}
			TokenResponse response = TokenResponse.parse(httpResponse);
			if (!response.indicatesSuccess()) {
				ErrorObject err = response.toErrorResponse().getErrorObject();
				if (sipLogger != null) {
					sipLogger.warning("OAuth2 token fetch failed: " + err.getCode()
							+ " - " + err.getDescription());
				}
				Remotes.failed("oauth", remote, "token refused: " + err.getCode(), (System.nanoTime() - started) / 1_000_000L);
				return null;
			}
			Remotes.succeeded("oauth", remote, (System.nanoTime() - started) / 1_000_000L);
			AccessTokenResponse success = response.toSuccessResponse();
			AccessToken at = success.getTokens().getAccessToken();
			accessToken = at.getValue();
			long lifetime = at.getLifetime();
			expiresAtMs = now + (lifetime > 0 ? lifetime * 1000L : 3600_000L);
			return accessToken;
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("OAuth2 token fetch failed: " + e.getMessage());
			}
			if (traced != null && sent) {
				trace(traced, CallStep.IN, HttpSteps.RESPONSE, "failed", "No response: " + e.getMessage());
			}
			Remotes.failed("oauth", remote, Remotes.reason(e), (System.nanoTime() - started) / 1_000_000L);
			return null;
		}
	}

	private static void trace(String sessionId, String direction, String kind, String label, String text) {
		TraceLog.append(HttpSteps.step(sessionId, System.currentTimeMillis(), direction, kind, label, "OAuth token",
				text));
	}

	/// The token request as it goes on the wire. Its body is a form of
	/// credentials (client secret, password, assertion), each value masked;
	/// `client_secret_basic` puts the secret in the masked `Authorization`.
	private static String requestText(com.nimbusds.oauth2.sdk.http.HTTPRequest request) {
		StringBuilder sb = new StringBuilder();
		sb.append(request.getMethod()).append(' ').append(request.getURL()).append('\n');
		request.getHeaderMap().forEach((name, values) -> {
			for (String value : values) {
				HttpSteps.appendHeader(sb, name, value);
			}
		});
		sb.append('\n');
		if (request.getBody() != null) {
			sb.append(HttpSteps.maskForm(request.getBody()));
		}
		return sb.toString();
	}

	/// The answer, its tokens masked.
	private static String responseText(com.nimbusds.oauth2.sdk.http.HTTPResponse response) {
		StringBuilder sb = new StringBuilder();
		sb.append("HTTP ").append(response.getStatusCode()).append('\n');
		response.getHeaderMap().forEach((name, values) -> {
			for (String value : values) {
				HttpSteps.appendHeader(sb, name, value);
			}
		});
		sb.append('\n');
		if (response.getBody() != null) {
			sb.append(HttpSteps.maskJson(response.getBody()));
		}
		return sb.toString();
	}

	/// Subclasses construct the Nimbus [TokenRequest] embodying their
	/// specific RFC 6749 grant + client-authentication combination.
	protected abstract TokenRequest buildTokenRequest(Context ctx) throws Exception;

	// ---- helpers for subclasses ----

	protected URI endpoint(Context ctx) throws Exception {
		return new URI(ctx.resolve(tokenUrl));
	}

	protected Scope scope(Context ctx) {
		if (scope == null || scope.isEmpty()) return null;
		String resolved = ctx.resolve(scope);
		return (resolved == null || resolved.isEmpty()) ? null : Scope.parse(resolved);
	}

	/// Builds a `client_secret_basic` client authentication when both
	/// `clientId` and `clientSecret` are non-null; returns null otherwise
	/// (signalling a public client, where `clientId` — if any — should
	/// be passed via the alternative [TokenRequest] constructor).
	protected ClientAuthentication clientSecretBasic(String clientId, String clientSecret, Context ctx) {
		if (clientId == null || clientSecret == null) return null;
		return new ClientSecretBasic(
				new ClientID(ctx.resolve(clientId)),
				new Secret(ctx.resolve(clientSecret)));
	}

	/// Convenience: construct a TokenRequest dispatching between
	/// authenticated-client, public-client-with-id, and anonymous forms.
	protected TokenRequest newTokenRequest(Context ctx,
			com.nimbusds.oauth2.sdk.AuthorizationGrant grant,
			String clientId, String clientSecret) throws Exception {
		ClientAuthentication clientAuth = clientSecretBasic(clientId, clientSecret, ctx);
		if (clientAuth != null) {
			return new TokenRequest(endpoint(ctx), clientAuth, grant, scope(ctx));
		}
		if (clientId != null) {
			return new TokenRequest(endpoint(ctx),
					new ClientID(ctx.resolve(clientId)), grant, scope(ctx));
		}
		return new TokenRequest(endpoint(ctx), grant);
	}
}
