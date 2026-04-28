package org.vorpal.blade.framework.v3.configuration.auth;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.token.RefreshToken;

/// OAuth 2.0 Refresh Token grant (RFC 6749 §6).
///
/// Uses a previously-issued refresh token to obtain access tokens on
/// demand. Useful when the initial authorization-code dance has
/// already happened (out of band) and the refresh token has been
/// persisted for iRouter's use — avoids requiring the resource owner's
/// password in the config.
@JsonPropertyOrder({ "type", "tokenUrl", "refreshToken", "clientId", "clientSecret",
		"scope", "refreshSkewSeconds" })
public class OAuth2RefreshTokenAuthentication extends AbstractOAuth2Authentication {
	private static final long serialVersionUID = 1L;

	private String refreshToken;
	private String clientId;
	private String clientSecret;

	@JsonPropertyDescription("Refresh token issued by an earlier authorization flow; supports ${var}")
	@FormLayout(password = true)
	public String getRefreshToken() {
		return refreshToken;
	}

	public void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
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
		RefreshTokenGrant grant = new RefreshTokenGrant(new RefreshToken(ctx.resolve(refreshToken)));
		return newTokenRequest(ctx, grant, clientId, clientSecret);
	}
}
