package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.vorpal.blade.framework.v3.configuration.Context;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// HTTP Basic authentication. Sends
/// `Authorization: Basic <base64(username:password)>`.
@JsonPropertyOrder({ "type", "username", "password" })
public class BasicAuthentication extends Authentication {
	private static final long serialVersionUID = 1L;

	private String username;
	private String password;

	public BasicAuthentication() {
	}

	public BasicAuthentication(String username, String password) {
		this.username = username;
		this.password = password;
	}

	@JsonPropertyDescription("Username; supports ${var}")
	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	@JsonPropertyDescription("Password; supports ${var}")
	@FormLayout(password = true)
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		String u = (username == null) ? "" : ctx.resolve(username);
		String p = (password == null) ? "" : ctx.resolve(password);
		String encoded = Base64.getEncoder()
				.encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
		reqBuilder.header("Authorization", "Basic " + encoded);
	}
}
