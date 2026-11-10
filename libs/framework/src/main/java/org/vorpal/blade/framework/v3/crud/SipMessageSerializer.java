package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/// Renders a [SipServletMessage] (typically a parsed-and-mutated
/// [org.vorpal.blade.framework.sip.DetachedRequest] /
/// [org.vorpal.blade.framework.sip.DetachedResponse]) back to RFC 3261
/// wire text for the preview endpoint to return to the operator.
public class SipMessageSerializer implements Serializable {
	private static final long serialVersionUID = 1L;

	private SipMessageSerializer() {
	}

	public static String serialize(SipServletMessage msg) {
		StringBuilder sb = new StringBuilder();
		appendStartLine(sb, msg);
		appendHeaders(sb, msg);
		sb.append("\r\n");
		appendBody(sb, msg);
		return sb.toString();
	}

	private static void appendStartLine(StringBuilder sb, SipServletMessage msg) {
		if (msg instanceof SipServletRequest) {
			SipServletRequest req = (SipServletRequest) msg;
			String uri = null;
			if (req.getRequestURI() != null) {
				uri = req.getRequestURI().toString();
			} else {
				// Fallback to the raw URI string the parser stashed when
				// no SipFactory was available to build a typed URI.
				Object raw = req.getAttribute(SipMessageParser.ATTR_RAW_URI);
				if (raw instanceof String) uri = (String) raw;
			}
			if (uri == null) uri = "sip:unknown";
			sb.append(req.getMethod()).append(' ').append(uri).append(" SIP/2.0\r\n");
		} else if (msg instanceof SipServletResponse) {
			// A rule's status rewrite is pending until the response is sent
			// (CrudHook.restatus); show the response as it would go out.
			SipServletResponse resp = (SipServletResponse) msg;
			Object pendingStatus = resp.getAttribute(MessageHelper.PENDING_STATUS);
			Object pendingReason = resp.getAttribute(MessageHelper.PENDING_REASON);
			sb.append("SIP/2.0 ").append(pendingStatus instanceof Integer ? pendingStatus : resp.getStatus());
			String reason = (pendingReason instanceof String) ? (String) pendingReason : resp.getReasonPhrase();
			if (reason != null && !reason.isEmpty()) sb.append(' ').append(reason);
			sb.append("\r\n");
		}
	}

	private static void appendHeaders(StringBuilder sb, SipServletMessage msg) {
		// Emit headers in the original input order (stashed by the parser),
		// then any headers added by operations after parsing. Without this
		// the underlying DetachedMessage's HashMap iteration shuffles every
		// header on every run and makes the diff impossible to read.
		LinkedHashSet<String> emitted = new LinkedHashSet<>();
		boolean ctSeen = false;

		Object orderAttr = msg.getAttribute(SipMessageParser.ATTR_HEADER_ORDER);
		if (orderAttr instanceof List) {
			for (Object o : (List<?>) orderAttr) {
				if (!(o instanceof String)) continue;
				String name = (String) o;
				if (!appendAll(sb, msg, name)) continue; // dropped by a delete op
				emitted.add(name.toLowerCase());
				if ("content-type".equalsIgnoreCase(name)) ctSeen = true;
			}
		}

		// Append anything in the message that wasn't in the original
		// order — these are headers added by create operations. Stable
		// regardless of the underlying map's iteration quirks.
		List<String> all = msg.getHeaderNameList();
		if (all != null) {
			for (String name : all) {
				if (emitted.contains(name.toLowerCase())) continue;
				if (!appendAll(sb, msg, name)) continue;
				emitted.add(name.toLowerCase());
				if ("content-type".equalsIgnoreCase(name)) ctSeen = true;
			}
		}

		// `setContent(value, type)` can stash the content-type on the
		// message without going through setHeader, so emit it explicitly
		// when we didn't see it among the headers above.
		if (!ctSeen) {
			String ct = msg.getContentType();
			if (ct != null) sb.append("Content-Type: ").append(ct).append("\r\n");
		}
	}

	/// Writes every occurrence of `name`; false if there is none.
	private static boolean appendAll(StringBuilder sb, SipServletMessage msg, String name) {
		boolean any = false;
		for (java.util.ListIterator<String> it = msg.getHeaders(name); it != null && it.hasNext();) {
			sb.append(name).append(": ").append(it.next()).append("\r\n");
			any = true;
		}
		return any;
	}

	private static void appendBody(StringBuilder sb, SipServletMessage msg) {
		try {
			Object content = msg.getContent();
			if (content == null) return;
			if (content instanceof String) {
				sb.append((String) content);
			} else if (content instanceof byte[]) {
				sb.append(new String((byte[]) content));
			}
		} catch (Exception ignore) {
		}
	}
}
