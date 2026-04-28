package org.vorpal.blade.framework.v3.configuration.auth;

import java.io.Serializable;
import java.net.http.HttpRequest;

import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/// Authentication scheme applied to a
/// [org.vorpal.blade.framework.v3.configuration.connectors.RestConnector]
/// request just before it's sent. Polymorphic via `type`:
///
/// **Static credentials (native)**
///
/// - `basic` — HTTP Basic (`Authorization: Basic <base64>`)
/// - `bearer` — static Bearer token (`Authorization: Bearer ${token}`)
/// - `apikey` — arbitrary header (e.g. `X-API-Key: ${value}`)
///
/// **OAuth 2.0 grants (Nimbus-backed, token cached + refreshed)**
///
/// - `oauth2-password` — RFC 6749 §4.3 — Resource Owner Password
///   Credentials (ROPC). Username + password exchanged for tokens.
/// - `oauth2-client` — RFC 6749 §4.4 — Client Credentials. Machine-
///   to-machine; no end user.
/// - `oauth2-refresh-token` — RFC 6749 §6 — refresh-token grant. Uses
///   a pre-issued refresh token to obtain access tokens on demand.
/// - `oauth2-jwt-bearer` — RFC 7523 — signed JWT assertion exchanged
///   for access token (service-account flows).
/// - `oauth2-saml-bearer` — RFC 7522 — SAML 2.0 assertion exchanged
///   for access token (enterprise SSO flows).
///
/// `${var}` substitution from the session [Context] is honored on every
/// field — so credentials can come from environment variables, system
/// properties, or values earlier pipeline stages wrote to the context
/// (e.g. `${apiKey}` from a customers-table lookup).
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = BasicAuthentication.class, name = "basic"),
		@JsonSubTypes.Type(value = BearerAuthentication.class, name = "bearer"),
		@JsonSubTypes.Type(value = ApiKeyAuthentication.class, name = "apikey"),
		@JsonSubTypes.Type(value = OAuth2PasswordAuthentication.class, name = "oauth2-password"),
		@JsonSubTypes.Type(value = OAuth2ClientCredentialsAuthentication.class, name = "oauth2-client"),
		@JsonSubTypes.Type(value = OAuth2RefreshTokenAuthentication.class, name = "oauth2-refresh-token"),
		@JsonSubTypes.Type(value = OAuth2JwtBearerAuthentication.class, name = "oauth2-jwt-bearer"),
		@JsonSubTypes.Type(value = OAuth2SamlBearerAuthentication.class, name = "oauth2-saml-bearer"),
		@JsonSubTypes.Type(value = HmacAuthentication.class, name = "hmac"),
		@JsonSubTypes.Type(value = AwsSigV4Authentication.class, name = "aws-sigv4")
})
public abstract class Authentication implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Stamp whatever credential headers this scheme needs onto
	/// `reqBuilder`. Called synchronously just before the RestConnector
	/// dispatches the request; OAuth subtypes may block briefly on a
	/// token-endpoint call. Runs on a worker thread, never the SIP
	/// container thread.
	///
	/// The simple overload is all most schemes need — they only care
	/// about `${var}` resolution and stamping an `Authorization`
	/// header. Schemes that must incorporate the full request into the
	/// signature (HMAC of the body, AWS SigV4 of the canonical request)
	/// override the three-arg [#applyTo(HttpRequest.Builder, Context, RequestSignature)]
	/// below; RestConnector calls that one, and the default delegates
	/// to this simpler form.
	public abstract void applyTo(HttpRequest.Builder reqBuilder, Context ctx);

	/// Extended overload for request-signing schemes that need access
	/// to the HTTP method, resolved URL, and resolved body. Default
	/// implementation delegates to [#applyTo(HttpRequest.Builder, Context)]
	/// — override only when the signature covers request content.
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx, RequestSignature request) {
		applyTo(reqBuilder, ctx);
	}

	/// The resolved HTTP method / URL / body visible to a request-signing
	/// [Authentication] subtype at stamp time. Kept as a tiny value
	/// object so future signing schemes can add fields (query string,
	/// timestamp, canonical headers) without widening the [#applyTo]
	/// signature.
	public static final class RequestSignature implements Serializable {
		private static final long serialVersionUID = 1L;
		private final String method;
		private final String url;
		private final String body;

		public RequestSignature(String method, String url, String body) {
			this.method = (method == null) ? "GET" : method.toUpperCase();
			this.url = (url == null) ? "" : url;
			this.body = (body == null) ? "" : body;
		}

		public String method() { return method; }
		public String url()    { return url; }
		public String body()   { return body; }
	}
}
