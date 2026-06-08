package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.crud.MessageHelper;
import org.vorpal.blade.framework.v3.crud.MimeHelper;
import org.vorpal.blade.framework.v3.crud.MimeHelper.MimePart;

/// A parsed SIP-message template: an optional request line, `Name: value`
/// headers, a blank line, and an optional body — the format operators drop
/// into `config/custom/vorpal/_templates/`. [#apply] merges it into a live
/// outbound request:
///
/// - request line (if present) → `setRequestURI`
/// - headers → `setHeader`, with system-header awareness (`Contact`
///   contributes only its parameters — e.g. `+sip.src` for SIPREC — via
///   [MessageHelper]); a single rejected header never aborts the rest
/// - body handling depends on the template `Content-Type`:
///   - empty body → leave the request content alone
///   - `multipart/*` → rebuild the body as a multipart whose first part is
///     the SDP, followed by the template's non-SDP parts (e.g. SIPREC
///     `application/rs-metadata+xml`)
///   - any other body → applied only when the request has no content yet
///     (originated calls); an existing body — the softphone's SDP — is
///     never overwritten by a single-part template
///
/// **SDP precedence**: a template's `application/sdp` part — present in
/// SIPREC samples whose `a=label:…` lines must match the metadata —
/// overrides the request's SDP. Templates without an SDP part fall back to
/// the request's SDP so non-SIPREC flows still negotiate media end-to-end.
public class SipMessageTemplate implements Serializable {
	private static final long serialVersionUID = 1L;

	/// One template header line, order-preserving.
	private static class Header implements Serializable {
		private static final long serialVersionUID = 1L;
		final String name;
		final String value;

		Header(String name, String value) {
			this.name = name;
			this.value = value;
		}
	}

	private String requestUri;
	private final List<Header> headers = new ArrayList<>();
	private String contentType;
	private String body = "";

	private SipMessageTemplate() {
	}

	/// Parses raw template text. Never throws on sloppy input — malformed
	/// lines are skipped, matching the operator-file tolerance of the
	/// original test-uac template loader.
	public static SipMessageTemplate parse(String raw) {
		SipMessageTemplate t = new SipMessageTemplate();
		int blank = findBlankLine(raw);
		String headerSection = (blank >= 0) ? raw.substring(0, blank) : raw;
		t.body = (blank >= 0) ? raw.substring(blank).trim() : "";

		boolean firstLine = true;
		for (String line : headerSection.split("\\r?\\n")) {
			line = line.trim();
			if (line.isEmpty()) continue;

			if (firstLine) {
				firstLine = false;
				if (isRequestLine(line)) {
					t.requestUri = requestLineUri(line);
					continue;
				}
			}

			int colon = line.indexOf(':');
			if (colon <= 0) continue;
			String name = line.substring(0, colon).trim();
			String value = line.substring(colon + 1).trim();

			if ("Content-Type".equalsIgnoreCase(name)) {
				t.contentType = value;
			} else {
				t.headers.add(new Header(name, value));
			}
		}
		return t;
	}

	/// Merges this template into a live request. See the class javadoc for
	/// the header and body semantics.
	public void apply(SipServletRequest request) {
		if (requestUri != null) {
			try {
				request.setRequestURI(AsyncSipServlet.getSipFactory().createURI(requestUri));
			} catch (Exception e) {
				warn(request, "request line rejected: " + e.getMessage());
			}
		}

		// Per-header try/catch so a single rejected header (e.g. a system
		// header someone added to a template) doesn't abort the rest of the
		// template, including the body handling further down.
		for (Header h : headers) {
			try {
				MessageHelper.setAttributeValue(request, h.name, h.value, null);
			} catch (Exception e) {
				warn(request, "skipping header '" + h.name + "': " + e.getMessage());
			}
		}

		applyBody(request);
	}

	private void applyBody(SipServletRequest request) {
		if (body.isEmpty()) return;

		try {
			if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
				mergeMultipart(request);
				return;
			}
			// Single-part template body: only fills an empty request (an
			// originated call). SDP from a real softphone is sacred.
			if (request.getContent() == null) {
				if (contentType == null) {
					warn(request, "template body has no Content-Type header; leaving body alone");
					return;
				}
				request.setContent(body.getBytes(), contentType);
			}
		} catch (Exception e) {
			warn(request, "body application failed: " + e.getMessage());
		}
	}

	/// Rebuild the request body as a multipart whose first part is the SDP
	/// (template's if present, else the request's existing one) followed by
	/// the template's non-SDP parts, using the template's declared boundary.
	private void mergeMultipart(SipServletRequest request) throws Exception {
		String boundary = MimeHelper.extractBoundary(contentType);
		if (boundary == null) {
			warn(request, "multipart template Content-Type missing boundary: " + contentType);
			return;
		}

		List<MimePart> templateParts = MimeHelper.parseParts(body, contentType);

		MimePart sdpPart = null;
		for (MimePart p : templateParts) {
			if (isSdp(p)) {
				sdpPart = p;
				break;
			}
		}
		if (sdpPart == null) {
			String existingSdp = MessageHelper.getAttributeValue(request, "body", "application/sdp");
			if (existingSdp == null) {
				warn(request, "no SDP in template or request; leaving body alone");
				return;
			}
			sdpPart = new MimePart("application/sdp", existingSdp);
		}

		List<MimePart> out = new ArrayList<>();
		out.add(sdpPart);
		for (MimePart p : templateParts) {
			if (!isSdp(p)) out.add(p);
		}

		request.setContent(MimeHelper.compose(out, boundary).getBytes(), contentType);
	}

	private static boolean isSdp(MimePart p) {
		String ct = p.getContentType();
		return ct != null && ct.toLowerCase().startsWith("application/sdp");
	}

	private static void warn(SipServletRequest request, String message) {
		SettingsManager.getSipLogger().warning(request, "SipMessageTemplate - " + message);
	}

	/// True if the line looks like a SIP request line: `METHOD <URI> SIP/2.0`.
	private static boolean isRequestLine(String line) {
		return line.contains(" SIP/") && line.split("\\s+").length >= 3;
	}

	private static String requestLineUri(String line) {
		String[] parts = line.split("\\s+", 3);
		return (parts.length >= 3) ? parts[1] : null;
	}

	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0) return idx2;
		if (idx2 < 0) return idx;
		return Math.min(idx, idx2);
	}
}
