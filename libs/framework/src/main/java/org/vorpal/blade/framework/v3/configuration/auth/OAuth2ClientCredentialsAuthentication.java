package org.vorpal.blade.framework.v3.configuration.auth;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.TokenRequest;

/// OAuth 2.0 Client Credentials grant (RFC 6749 §4.4).
///
/// Machine-to-machine: the client authenticates with its own credentials
/// and receives an access token representing itself. No end user.
@JsonPropertyOrder({ "type", "tokenUrl", "clientId", "clientSecret", "scope", "refreshSkewSeconds" })
public class OAuth2ClientCredentialsAuthentication extends AbstractOAuth2Authentication {
	private static final long serialVersionUID = 1L;

	private String clientId;
	private String clientSecret;

	@JsonPropertyDescription("OAuth 2.0 client identifier; supports ${var}")
	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	@JsonPropertyDescription("OAuth 2.0 client secret; supports ${var}")
	@FormLayout(password = true)
	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	@Override
	protected TokenRequest buildTokenRequest(Context ctx) throws Exception {
		return newTokenRequest(ctx, new ClientCredentialsGrant(), clientId, clientSecret);
	}
}
