package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.URI;
import java.net.http.HttpRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

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
		try {
			TokenRequest req = buildTokenRequest(ctx);
			TokenResponse response = TokenResponse.parse(req.toHTTPRequest().send());
			if (!response.indicatesSuccess()) {
				ErrorObject err = response.toErrorResponse().getErrorObject();
				if (sipLogger != null) {
					sipLogger.warning("OAuth2 token fetch failed: " + err.getCode()
							+ " - " + err.getDescription());
				}
				return null;
			}
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
			return null;
		}
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
