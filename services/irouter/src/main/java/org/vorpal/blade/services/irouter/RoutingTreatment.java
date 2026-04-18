package org.vorpal.blade.services.irouter;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// iRouter's concrete treatment: where to send the outbound INVITE and
/// what headers to apply on the way out. The strongly-typed payload
/// carried by each [org.vorpal.blade.framework.v3.configuration.translations.Translation]
/// in an iRouter config.
///
/// Templated `${var}` placeholders in either field are resolved against
/// the session [org.vorpal.blade.framework.v3.configuration.Context] at
/// match time, so treatments can carry values extracted by upstream
/// adapters (e.g. `sip:${to-user}@queue.example.com`).
@JsonPropertyOrder({ "requestUri", "headers" })
public class RoutingTreatment implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Destination SIP URI for the outbound INVITE")
	private String requestUri;

	@JsonPropertyDescription("Headers to set on the outbound INVITE (name → value template)")
	private Map<String, String> headers;

	public RoutingTreatment() {
	}

	public RoutingTreatment(String requestUri) {
		this.requestUri = requestUri;
	}

	public RoutingTreatment(String requestUri, Map<String, String> headers) {
		this.requestUri = requestUri;
		this.headers = headers;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	/// Fluent helper: add a single outbound header, allocating the map if needed.
	public RoutingTreatment addHeader(String name, String value) {
		if (headers == null) {
			headers = new LinkedHashMap<>();
		}
		headers.put(name, value);
		return this;
	}
}
