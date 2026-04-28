package org.vorpal.blade.framework.v3.configuration.routing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// The routing decision payload: where to proxy the call and what
/// outbound INVITE headers to stamp.
///
/// Produced by the top-level [Routing] of a [org.vorpal.blade.framework.v3.configuration.RouterConfiguration].
/// Both `requestUri` and every value in `headers` are `${var}`-interpolated
/// against the session [org.vorpal.blade.framework.v3.configuration.Context]
/// at decision time, so templates like `"sip:${destNum}@${carrier}"` or
/// `"X-Customer-Id": "${customerId}"` resolve against whatever the
/// pipeline put into the context.
///
/// `conditionalHeaders` is the opt-in escape hatch: each entry stamps
/// its header only when its `when` expression evaluates true. Unconditional
/// headers stay in the plain `headers` map for readability.
@JsonPropertyOrder({ "description", "requestUri", "headers", "conditionalHeaders" })
public class Route implements Serializable {
	private static final long serialVersionUID = 1L;

	private String description;
	private String requestUri;
	private Map<String, String> headers;
	private List<ConditionalHeader> conditionalHeaders;

	public Route() {
	}

	public Route(String requestUri) {
		this.requestUri = requestUri;
	}

	public Route(String requestUri, Map<String, String> headers) {
		this.requestUri = requestUri;
		this.headers = headers;
	}

	@JsonPropertyDescription("Human-readable description of this route")
	@FormLayout(wide = true)
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Destination SIP URI for the outbound INVITE; supports ${var}")
	@FormLayout(wide = true)
	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	@JsonPropertyDescription("Outbound INVITE headers to set unconditionally (name → value template)")
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	@JsonPropertyDescription("Outbound INVITE headers stamped only when their `when` expression evaluates true")
	public List<ConditionalHeader> getConditionalHeaders() {
		return conditionalHeaders;
	}

	public void setConditionalHeaders(List<ConditionalHeader> conditionalHeaders) {
		this.conditionalHeaders = conditionalHeaders;
	}

	/// Fluent helper: add one unconditional outbound header, allocating
	/// the map if needed.
	public Route addHeader(String name, String value) {
		if (headers == null) {
			headers = new LinkedHashMap<>();
		}
		headers.put(name, value);
		return this;
	}

	/// Fluent helper: add one conditional outbound header, allocating
	/// the list if needed.
	public Route addConditionalHeader(String name, String value, String when) {
		if (conditionalHeaders == null) {
			conditionalHeaders = new ArrayList<>();
		}
		conditionalHeaders.add(new ConditionalHeader(name, value, when));
		return this;
	}
}
