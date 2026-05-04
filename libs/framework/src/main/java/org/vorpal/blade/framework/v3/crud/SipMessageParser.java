package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.framework.v2.testing.DummyResponse;

/// Parses raw SIP wire text into a [DummyRequest] or [DummyResponse]. Used
/// by the preview endpoint so operators can paste a captured SIP message
/// (e.g. from a wireshark trace or service log) and see what a rule set
/// would do to it.
///
/// Line folding (RFC 3261 §7.3.1) is honoured — header continuation lines
/// starting with whitespace are joined onto the prior header. Bodies pass
/// through unchanged; multipart parsing happens later via [MimeHelper] if
/// the rule operations target a specific MIME part.
public class SipMessageParser implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Message attribute holding the raw Request-URI as parsed. Used by
	/// [SipMessageSerializer] when [SipServletRequest#getRequestURI] is
	/// null — happens when no SipFactory is available (e.g. in the
	/// AdminServer-side preview WAR, which has no SIP container).
	public static final String ATTR_RAW_URI = "preview.rawUri";

	/// Message attribute holding the original header order (List<String>).
	/// [SipMessageSerializer] iterates it so the output preserves the input
	/// order; without this the underlying DummyMessage's HashMap iteration
	/// order shuffles every header on every run.
	public static final String ATTR_HEADER_ORDER = "preview.headerOrder";

	private SipMessageParser() {
	}

	/// Parses raw SIP text. Auto-detects request vs response from the start
	/// line. Throws [IllegalArgumentException] on malformed input.
	public static SipServletMessage parse(String text) throws Exception {
		if (text == null) throw new IllegalArgumentException("message text is null");

		// Normalise line endings up front so split below is deterministic.
		String normalised = text.replace("\r\n", "\n").replace("\r", "\n");

		int blank = normalised.indexOf("\n\n");
		String head;
		String body;
		if (blank < 0) {
			head = normalised;
			body = null;
		} else {
			head = normalised.substring(0, blank);
			body = normalised.substring(blank + 2);
		}

		String[] lines = head.split("\n");
		if (lines.length == 0) throw new IllegalArgumentException("message has no start line");

		String startLine = lines[0].trim();
		if (startLine.isEmpty()) throw new IllegalArgumentException("empty start line");

		// Apply line folding before parsing headers.
		String[] folded = unfold(lines);

		boolean isResponse = startLine.startsWith("SIP/");
		SipServletMessage msg = isResponse
				? buildResponse(startLine, folded)
				: buildRequest(startLine, folded);

		applyHeaders(msg, folded);
		applyBody(msg, body);
		return msg;
	}

	private static SipServletRequest buildRequest(String startLine, String[] foldedLines) throws Exception {
		// METHOD URI SIP/2.0
		String[] parts = startLine.split("\\s+", 3);
		if (parts.length < 3) throw new IllegalArgumentException("malformed request line: " + startLine);
		if (!parts[2].startsWith("SIP/")) {
			throw new IllegalArgumentException("malformed request line (no SIP/x.x version): " + startLine);
		}
		String method = parts[0];
		String requestUri = parts[1];

		String from = headerValue(foldedLines, "From");
		String to = headerValue(foldedLines, "To");
		if (from == null) from = "<sip:anonymous@unknown>";
		if (to == null) to = "<sip:anonymous@unknown>";

		DummyRequest req = new DummyRequest(method, from, to);
		req.setApplicationSession(new DummyApplicationSession("preview"));

		// Always stash the raw URI so the serializer has a fallback when
		// no SipFactory is available (AdminServer-side, no SIP container).
		req.setAttribute(ATTR_RAW_URI, requestUri);

		// Best-effort typed Request-URI: try the SipFactory if available.
		try {
			URI uri = AsyncSipServlet.getSipFactory().createURI(requestUri);
			req.setRequestURI(uri);
		} catch (Throwable ignore) {
		}

		return req;
	}

	private static SipServletMessage buildResponse(String startLine, String[] foldedLines) throws Exception {
		// SIP/2.0 200 OK Reason
		String[] parts = startLine.split("\\s+", 3);
		if (parts.length < 2) throw new IllegalArgumentException("malformed status line: " + startLine);
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("non-numeric status code: " + parts[1]);
		}
		String reason = (parts.length >= 3) ? parts[2] : "";

		// Synthesize an INVITE request to anchor the response. Operators
		// can override the assumed method via a `CSeq:` header — if it's
		// present we'll respect it.
		String cseq = headerValue(foldedLines, "CSeq");
		String method = "INVITE";
		if (cseq != null) {
			String[] cseqParts = cseq.trim().split("\\s+");
			if (cseqParts.length >= 2) method = cseqParts[1];
		}

		String from = headerValue(foldedLines, "From");
		String to = headerValue(foldedLines, "To");
		if (from == null) from = "<sip:anonymous@unknown>";
		if (to == null) to = "<sip:anonymous@unknown>";

		DummyRequest req = new DummyRequest(method, from, to);
		req.setApplicationSession(new DummyApplicationSession("preview"));
		return new DummyResponse(req, status, reason);
	}

	private static void applyHeaders(SipServletMessage msg, String[] foldedLines) {
		// LinkedHashSet to dedup repeated names (we keep the last value
		// because setHeader overwrites) while preserving first-seen order.
		LinkedHashSet<String> order = new LinkedHashSet<>();
		// Skip the start line; everything else `Name: value`.
		for (int i = 1; i < foldedLines.length; i++) {
			String line = foldedLines[i];
			if (line == null || line.isEmpty()) continue;
			int colon = line.indexOf(':');
			if (colon <= 0) continue; // malformed → drop
			String name = line.substring(0, colon).trim();
			String value = line.substring(colon + 1).trim();
			msg.setHeader(name, value);
			order.add(name);
		}
		msg.setAttribute(ATTR_HEADER_ORDER, new ArrayList<>(order));
	}

	private static void applyBody(SipServletMessage msg, String body) throws Exception {
		if (body == null || body.isEmpty()) return;
		String contentType = msg.getHeader("Content-Type");
		if (contentType == null) contentType = "application/octet-stream";
		// Strip a single trailing newline that's part of the message
		// envelope rather than the body content.
		if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
		msg.setContent(body, contentType);
	}

	/// RFC 3261 §7.3.1 line folding: a header continuation starts with
	/// whitespace and joins onto the previous line with a single space.
	/// Returns the same array length as input but with continuation lines
	/// blanked out, so caller can index by original line number.
	private static String[] unfold(String[] lines) {
		String[] out = new String[lines.length];
		int last = -1;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.isEmpty()) {
				out[i] = "";
				continue;
			}
			char first = line.charAt(0);
			if ((first == ' ' || first == '\t') && last >= 0) {
				out[last] = out[last] + " " + line.trim();
				out[i] = "";
			} else {
				out[i] = line;
				last = i;
			}
		}
		return out;
	}

	private static String headerValue(String[] foldedLines, String name) {
		for (int i = 1; i < foldedLines.length; i++) {
			String line = foldedLines[i];
			if (line == null || line.isEmpty()) continue;
			int colon = line.indexOf(':');
			if (colon <= 0) continue;
			if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
				return line.substring(colon + 1).trim();
			}
		}
		return null;
	}
}
