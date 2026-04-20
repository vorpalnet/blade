package org.vorpal.blade.framework.v3.configuration.auth;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.oauth2.sdk.SAML2BearerGrant;
import com.nimbusds.oauth2.sdk.TokenRequest;

/// OAuth 2.0 SAML 2.0 Bearer grant (RFC 7522).
///
/// Exchanges a base64url-encoded SAML 2.0 assertion for an access
/// token. Common in enterprise SSO flows where a SAML IdP issues the
/// assertion and the authorization server accepts it as proof of
/// identity.
///
/// iRouter does not mint the SAML assertion — `assertion` resolves to
/// a pre-encoded value supplied via context variable, environment
/// variable, or (rarely) hard-coded in config.
@JsonPropertyOrder({ "type", "tokenUrl", "assertion", "clientId", "clientSecret",
		"scope", "refreshSkewSeconds" })
public class OAuth2SamlBearerAuthentication extends AbstractOAuth2Authentication {
	private static final long serialVersionUID = 1L;

	private String assertion;
	private String clientId;
	private String clientSecret;

	@JsonPropertyDescription("Base64url-encoded SAML 2.0 assertion; supports ${var}")
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
		Base64URL encoded = new Base64URL(ctx.resolve(assertion));
		SAML2BearerGrant grant = new SAML2BearerGrant(encoded);
		return newTokenRequest(ctx, grant, clientId, clientSecret);
	}
}
