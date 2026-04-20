package org.vorpal.blade.framework.v3.configuration.routing;

import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// [Routing] that always returns a single [Route]. No lookup.
///
/// Use when the pipeline's enrichment has already produced everything
/// needed to compose the destination with `${var}` interpolation and a
/// table lookup would be redundant. The Route's fields are inlined at
/// the top level of the `routing` block so the JSON stays flat:
///
/// ```json
/// "routing": {
///   "type": "direct",
///   "description": "Always proxy to the customer's contact center",
///   "requestUri": "sip:${destNum}@${customerPbx}",
///   "headers": { "X-Customer-Id": "${customerId}" }
/// }
/// ```
@JsonPropertyOrder({ "type", "description", "requestUri", "headers" })
public class DirectRouting extends Routing {
	private static final long serialVersionUID = 1L;

	private String description;
	private String requestUri;
	private Map<String, String> headers;

	public DirectRouting() {
	}

	public DirectRouting(String requestUri) {
		this.requestUri = requestUri;
	}

	@JsonPropertyDescription("Human-readable description of this route")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Destination SIP URI for the outbound INVITE; supports ${var}")
	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	@JsonPropertyDescription("Outbound INVITE headers to set (name → value template)")
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	@Override
	public Route decide(Context ctx) {
		Route r = new Route();
		r.setDescription(description);
		r.setRequestUri(requestUri);
		r.setHeaders(headers);
		return r;
	}
}
