package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.http.HttpRequest;

import org.vorpal.blade.framework.v3.configuration.Context;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Static Bearer token. Sends `Authorization: Bearer ${token}`.
///
/// For OAuth-issued tokens that need automatic refresh, use one of the
/// `oauth2-*` subtypes instead; this scheme is appropriate when the
/// token is long-lived, externally managed, or resolved from a context
/// variable that an upstream pipeline stage writes.
@JsonPropertyOrder({ "type", "token" })
public class BearerAuthentication extends Authentication {
	private static final long serialVersionUID = 1L;

	private String token;

	public BearerAuthentication() {
	}

	public BearerAuthentication(String token) {
		this.token = token;
	}

	@JsonPropertyDescription("Bearer token for the Authorization header; supports ${var}")
	@FormLayout(password = true)
	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		if (token == null) return;
		reqBuilder.header("Authorization", "Bearer " + ctx.resolve(token));
	}
}
