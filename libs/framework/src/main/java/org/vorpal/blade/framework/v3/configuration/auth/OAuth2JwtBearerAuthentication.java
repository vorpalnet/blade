package org.vorpal.blade.framework.v3.configuration.auth;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.JWTBearerGrant;
import com.nimbusds.oauth2.sdk.TokenRequest;

/// OAuth 2.0 JWT Bearer grant (RFC 7523).
///
/// Exchanges a pre-signed JWT assertion for an access token. Useful for
/// service-account flows where the client proves its identity by
/// presenting a JWT signed with a private key whose public key the
/// authorization server trusts (e.g. Google Cloud service accounts,
/// many enterprise STS deployments).
///
/// This subtype assumes the JWT has been constructed and signed
/// elsewhere and is provided as a serialized string. iRouter does not
/// sign JWTs itself — the `assertion` field typically resolves to a
/// context variable populated by an upstream step, an environment
/// variable, or a short-lived value written to the config.
@JsonPropertyOrder({ "type", "tokenUrl", "assertion", "clientId", "clientSecret",
		"scope", "refreshSkewSeconds" })
public class OAuth2JwtBearerAuthentication extends AbstractOAuth2Authentication {
	private static final long serialVersionUID = 1L;

	private String assertion;
	private String clientId;
	private String clientSecret;

	@JsonPropertyDescription("Serialized signed JWT assertion (compact form); supports ${var}")
	@FormLayout(password = true)
	public String getAssertion() {
		return assertion;
	}

	public void setAssertion(String assertion) {
		this.assertion = assertion;
	}

	@JsonPropertyDescription("Optional OAuth 2.0 client identifier; supports ${var}")
	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	@JsonPropertyDescription("Optional OAuth 2.0 client secret; supports ${var}")
	@FormLayout(password = true)
	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	@Override
	protected TokenRequest buildTokenRequest(Context ctx) throws Exception {
		SignedJWT jwt = SignedJWT.parse(ctx.resolve(assertion));
		JWTBearerGrant grant = new JWTBearerGrant(jwt);
		return newTokenRequest(ctx, grant, clientId, clientSecret);
	}
}
