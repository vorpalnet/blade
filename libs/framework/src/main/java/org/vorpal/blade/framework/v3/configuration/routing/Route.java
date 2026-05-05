package org.vorpal.blade.framework.v3.configuration.routing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// The routing decision payload — what to do with the inbound
/// INVITE once the pipeline has finished enriching the [Context].
///
/// A Route is either a **forward** or a **direct response**:
///
/// - **Forward** — `requestUri` is set, `statusCode` is null. The
///   call goes downstream; how depends on the subclass of
///   [org.vorpal.blade.framework.v3.irouter.IRouterInvite] handling it.
///   Plain iRouter proxies to the URI; SecureLogix returns
///   `302 Moved Temporarily` with the URI in `Contact`.
///
/// - **Direct response** — `statusCode` is set. The router answers
///   the inbound INVITE itself with `<statusCode> <reasonPhrase>`
///   and is done — no proxy, no redirect, no downstream leg. Use
///   for screening verdicts that should produce a SIP-correct
///   final response (e.g. `603 Decline` for a blocked call,
///   `403 Forbidden` for a policy reject). When `statusCode` is
///   set, `requestUri` is ignored.
///
/// Produced by the top-level [Routing] of a [org.vorpal.blade.framework.v3.configuration.RouterConfiguration].
/// `requestUri`, `reasonPhrase`, and every header value are
/// `${var}`-interpolated against the session [org.vorpal.blade.framework.v3.configuration.Context]
/// at decision time.
///
/// Headers (both unconditional and `conditionalHeaders`) are
/// stamped on whichever message the route produces — outbound
/// INVITE for a forward, the response itself for a direct response
/// or for a SecureLogix-style 302.
@JsonPropertyOrder({ "description", "requestUri", "statusCode", "reasonPhrase",
		"headers", "conditionalHeaders" })
public class Route implements Serializable {
	private static final long serialVersionUID = 1L;

	private String description;
	private String requestUri;
	private Integer statusCode;
	private String reasonPhrase;
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

	/// Direct-response route — the router answers the INVITE with
	/// `statusCode reasonPhrase` instead of forwarding. Use for
	/// screening verdicts that map to a SIP final response, e.g.
	/// `new Route(603, "Decline")` for a blocked call.
	public Route(int statusCode, String reasonPhrase) {
		this.statusCode = statusCode;
		this.reasonPhrase = reasonPhrase;
	}

	@JsonPropertyDescription("Human-readable description of this route")
	@FormLayout(wide = true)
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Destination SIP URI to forward to (proxy or 302 Contact). Supports ${var}. Ignored when statusCode is set.")
	@FormLayout(wide = true)
	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	@JsonPropertyDescription("If set, the router answers the INVITE with this SIP status code instead of forwarding (e.g. 603 to decline a screened call).")
	public Integer getStatusCode() {
		return statusCode;
	}

	public void setStatusCode(Integer statusCode) {
		this.statusCode = statusCode;
	}

	@JsonPropertyDescription("Reason phrase paired with statusCode, e.g. \"Decline\". Supports ${var}. Optional — the SIP container provides a default when omitted.")
	@FormLayout(wide = true)
	public String getReasonPhrase() {
		return reasonPhrase;
	}

	public void setReasonPhrase(String reasonPhrase) {
		this.reasonPhrase = reasonPhrase;
	}

	@JsonPropertyDescription("Headers to set unconditionally (name → value template). Stamped on the outbound INVITE for a forward route, or on the response for a direct-response or 302 route.")
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	@JsonPropertyDescription("Headers stamped only when their `when` expression evaluates true. Same target message as `headers` (request for forward, response for direct-response/302).")
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
