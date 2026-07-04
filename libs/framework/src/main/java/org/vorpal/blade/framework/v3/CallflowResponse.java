package org.vorpal.blade.framework.v3;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callback;

/// A callflow that answers the inbound request with one configurable response —
/// any status code, with an optional reason phrase, optional headers, and an
/// optional message body. The v3 successor to the hard-coded `Callflow481`
/// (deleted) and the v2 `CallflowResponseCode` (code + phrase only).
///
/// Fluent, so a rejection reads as one expression:
///
/// ```java
/// return new CallflowResponse(486, "Busy Here")
///         .addHeader("Retry-After", "120");
/// ```
///
/// or an answered INVITE, body and all:
///
/// ```java
/// return new CallflowResponse(200)
///         .setContent(sdp, "application/sdp")
///         .onAck(ack -> sipLogger.fine(ack, "answered"));
/// ```
///
/// Designed for FINAL responses: sending a 1xx leaves the transaction open and
/// this callflow does nothing further. A 2xx to an INVITE creates a dialog and
/// the far end will ACK — supply [#onAck] to receive it. Headers are added with
/// `addHeader` semantics (a repeated name adds another value, never clobbers);
/// naming a system header (From, To, Via, CSeq, ...) throws, as the container
/// dictates — that's a coding error, not a runtime condition to hide.
public class CallflowResponse extends Callflow {
	private static final long serialVersionUID = 1L;

	private final int statusCode;
	private final String reasonPhrase;
	private final Map<String, String> headers = new LinkedHashMap<>();
	private String content;
	private String contentType;
	private Callback<SipServletRequest> ackLambda;

	/// Respond with the code's default reason phrase.
	public CallflowResponse(int statusCode) {
		this(statusCode, null);
	}

	/// Respond with a custom reason phrase, e.g. `(486, "Busy Here")`.
	public CallflowResponse(int statusCode, String reasonPhrase) {
		this.statusCode = statusCode;
		this.reasonPhrase = reasonPhrase;
	}

	/// Add one header to the response (`addHeader` semantics — a repeated name
	/// in the response is possible by comma-joining values here; this map keeps
	/// one value per name).
	public CallflowResponse addHeader(String name, String value) {
		if (name != null && value != null) {
			headers.put(name, value);
		}
		return this;
	}

	/// Attach a message body. Both parts are required — a body without a
	/// `Content-Type` is malformed, so there is deliberately no single-argument
	/// variant.
	public CallflowResponse setContent(String content, String contentType) {
		this.content = content;
		this.contentType = contentType;
		return this;
	}

	/// Lambda invoked with the ACK when this response is a 2xx to an INVITE
	/// (a dialog forms and the far end acknowledges). Optional; irrelevant for
	/// non-2xx and non-INVITE responses.
	public CallflowResponse onAck(Callback<SipServletRequest> ackLambda) {
		this.ackLambda = ackLambda;
		return this;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		if (request == null) {
			return;
		}
		SipServletResponse response = (reasonPhrase != null)
				? request.createResponse(statusCode, reasonPhrase)
				: request.createResponse(statusCode);
		for (Map.Entry<String, String> header : headers.entrySet()) {
			response.addHeader(header.getKey(), header.getValue());
		}
		if (content != null) {
			response.setContent(content, contentType);
		}
		if (ackLambda != null) {
			sendResponse(response, ackLambda);
		} else {
			sendResponse(response);
		}
	}
}
