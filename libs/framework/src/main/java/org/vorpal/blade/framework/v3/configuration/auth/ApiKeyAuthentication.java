package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.http.HttpRequest;

import org.vorpal.blade.framework.v3.configuration.Context;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// API key carried in an arbitrary HTTP header (e.g. `X-API-Key`,
/// `X-Auth-Token`). Both the header name and its value are
/// `${var}`-resolvable.
@JsonPropertyOrder({ "type", "header", "value" })
public class ApiKeyAuthentication extends Authentication {
	private static final long serialVersionUID = 1L;

	private String header;
	private String value;

	public ApiKeyAuthentication() {
	}

	public ApiKeyAuthentication(String header, String value) {
		this.header = header;
		this.value = value;
	}

	@JsonPropertyDescription("HTTP header name, e.g. X-API-Key")
	public String getHeader() {
		return header;
	}

	public void setHeader(String header) {
		this.header = header;
	}

	@JsonPropertyDescription("Header value; supports ${var}")
	@FormLayout(password = true)
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		if (header == null || value == null) return;
		reqBuilder.header(ctx.resolve(header), ctx.resolve(value));
	}
}
