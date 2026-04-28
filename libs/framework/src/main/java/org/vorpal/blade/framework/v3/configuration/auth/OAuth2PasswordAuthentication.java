package org.vorpal.blade.framework.v3.configuration.auth;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nimbusds.oauth2.sdk.ResourceOwnerPasswordCredentialsGrant;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.Secret;

/// OAuth 2.0 Resource Owner Password Credentials grant (RFC 6749 §4.3).
///
/// Exchanges a username+password directly with the token endpoint for an
/// access token. Optional `clientId`/`clientSecret` select how the client
/// is authenticated with the server.
@JsonPropertyOrder({ "type", "tokenUrl", "username", "password", "clientId", "clientSecret",
		"scope", "refreshSkewSeconds" })
public class OAuth2PasswordAuthentication extends AbstractOAuth2Authentication {
	private static final long serialVersionUID = 1L;

	private String username;
	private String password;
	private String clientId;
	private String clientSecret;

	@JsonPropertyDescription("Resource owner username; supports ${var}")
	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	@JsonPropertyDescription("Resource owner password; supports ${var}")
	@FormLayout(password = true)
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
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
		ResourceOwnerPasswordCredentialsGrant grant = new ResourceOwnerPasswordCredentialsGrant(
				ctx.resolve(username),
				new Secret(ctx.resolve(password)));
		return newTokenRequest(ctx, grant, clientId, clientSecret);
	}
}
