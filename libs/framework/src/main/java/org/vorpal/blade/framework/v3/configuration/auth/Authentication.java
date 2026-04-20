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
		@JsonSubTypes.Type(value = OAuth2SamlBearerAuthentication.class, name = "oauth2-saml-bearer")
})
public abstract class Authentication implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Stamp whatever credential headers this scheme needs onto
	/// `reqBuilder`. Called synchronously just before the RestConnector
	/// dispatches the request; OAuth subtypes may block briefly on a
	/// token-endpoint call. Runs on a worker thread, never the SIP
	/// container thread.
	public abstract void applyTo(HttpRequest.Builder reqBuilder, Context ctx);
}
