package org.vorpal.blade.test.uas;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.CallflowHold;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.test.uas.callflows.TestInvite;
import org.vorpal.blade.test.uas.callflows.TestNotImplemented;
import org.vorpal.blade.test.uas.callflows.TestOkayResponse;
import org.vorpal.blade.test.uas.callflows.TestRefer;
import org.vorpal.blade.test.uas.config.TestUasConfig;
import org.vorpal.blade.test.uas.config.TestUasConfigSample;

/// SIP test server with two modes, chosen per-call from the initial INVITE's
/// Request-URI — no configuration toggle:
///
/// - **Strip-and-forward (B2BUA)** — when the INVITE carries none of
///   `status`, `delay`, or `refer`, the request is forwarded to its
///   Request-URI by [B2buaServlet]. [#callStarted] strips a multipart
///   (e.g. SIPREC) body down to just its `application/sdp` part so a plain
///   softphone can parse it.
/// - **Endpoint (UAS)** — when the INVITE carries `status`, `delay`, or
///   `refer`, the call is answered locally: [TestInvite] applies the
///   `status`/`delay` behavior, or [TestRefer] runs a transfer when `refer`
///   is present.
///
/// The chosen mode is stamped on the application session so in-dialog
/// requests (re-INVITE, BYE, …) route the same way as the initial INVITE.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UasServlet extends B2buaServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	/// App-session attribute marking a dialog as endpoint-mode (answered
	/// locally) vs. B2BUA-mode (forwarded). Set on the initial INVITE.
	private static final String ENDPOINT_ATTR = "test-uas.endpoint";

	public static SettingsManager<TestUasConfig> settingsManager;

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {

		if (request.getMethod().equals("INVITE") && request.isInitial()) {
			URI ruri = request.getRequestURI();
			boolean hasRefer = ruri.getParameter("refer") != null;
			boolean endpoint = hasRefer //
					|| ruri.getParameter("status") != null //
					|| ruri.getParameter("delay") != null;

			request.getApplicationSession().setAttribute(ENDPOINT_ATTR, endpoint);

			if (!endpoint) {
				return super.chooseCallflow(request); // B2BUA: strip + forward
			}
			return hasRefer ? new TestRefer() : new TestInvite();
		}

		// In-dialog / non-initial: route by the mode stamped on the INVITE.
		Boolean endpoint = (Boolean) request.getApplicationSession().getAttribute(ENDPOINT_ATTR);
		if (!Boolean.TRUE.equals(endpoint)) {
			return super.chooseCallflow(request); // B2BUA: in-dialog forwarding
		}

		switch (request.getMethod()) {
		case "INVITE":
			return new CallflowHold(); // re-INVITE → blackhole hold
		case "BYE":
		case "CANCEL":
		case "INFO":
			return new TestOkayResponse(); // 200 OK
		default:
			return new TestNotImplemented(); // 501
		}
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, TestUasConfig.class, new TestUasConfigSample());
		sipLogger.logConfiguration(settingsManager.getCurrent());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		// do nothing
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "UasServlet.callStarted");

		// Softphones choke on multipart bodies. Extract just the SDP
		// part from the incoming multipart content so the downstream
		// softphone sees a clean application/sdp body.
		stripMultipartToSdp(outboundRequest);
	}

	// ------------------------------------------------------------
	// Multipart → SDP stripping
	// ------------------------------------------------------------

	/// If the outbound request has a `multipart/*` body, parse the
	/// boundary-delimited parts, find the `application/sdp` part,
	/// and replace the request's content with just that SDP — so
	/// downstream softphones see a single-part message.
	///
	/// No-op if the content is already single-part, or if the SDP
	/// part isn't found.
	private void stripMultipartToSdp(SipServletRequest request) {
		try {
			String contentType = request.getContentType();
			if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
				return;
			}

			String boundary = extractBoundary(contentType);
			if (boundary == null) {
				sipLogger.warning(request, "UasServlet: multipart body without a boundary");
				return;
			}

			Object content = request.getContent();
			if (content == null)
				return;
			String body = (content instanceof String) ? (String) content : new String((byte[]) content);

			String sdp = extractSdpPart(body, boundary);
			if (sdp == null) {
				sipLogger.warning(request, "UasServlet: no application/sdp part in multipart body");
				return;
			}

			request.setContent(sdp.getBytes(), "application/sdp");
			sipLogger.fine(request, "UasServlet: stripped multipart → SDP (" + sdp.length() + " bytes)");
		} catch (Exception e) {
			sipLogger.warning(request, "UasServlet stripMultipartToSdp failed: " + e.getMessage());
		}
	}

	/// Extract `boundary=...` from a Content-Type header, handling
	/// both quoted and unquoted forms.
	private static String extractBoundary(String contentType) {
		int idx = contentType.toLowerCase().indexOf("boundary=");
		if (idx < 0)
			return null;
		String b = contentType.substring(idx + "boundary=".length()).trim();
		if (b.startsWith("\"")) {
			int end = b.indexOf('"', 1);
			return (end > 0) ? b.substring(1, end) : null;
		}
		int semi = b.indexOf(';');
		return (semi >= 0) ? b.substring(0, semi).trim() : b;
	}

	/// Walk the multipart body, find the part whose headers include
	/// `Content-Type: application/sdp`, and return its body.
	private static String extractSdpPart(String body, String boundary) {
		String separator = "--" + boundary;
		// Prepend CRLF when body starts at the boundary directly, otherwise the
		// first part lands in parts[0] and gets dropped as preamble.
		String working = body.startsWith(separator) ? "\r\n" + body : body;
		String[] parts = working.split("\\r?\\n" + Pattern.quote(separator));
		// parts[0] is the preamble before the first boundary.
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];

			// Remove CRLF right after the boundary marker.
			if (part.startsWith("\r\n"))
				part = part.substring(2);
			else if (part.startsWith("\n"))
				part = part.substring(1);

			// End boundary: "--boundary--" → the leading "--" is on
			// the part here, indicating we're past the last part.
			if (part.startsWith("--"))
				break;

			int blank = findBlankLine(part);
			if (blank < 0)
				continue;

			String partHeaders = part.substring(0, blank);
			String partBody = part.substring(blank).trim();

			// Trim trailing CRLF that precedes the next boundary.
			if (partBody.endsWith("\r\n"))
				partBody = partBody.substring(0, partBody.length() - 2);

			if (isSdpPart(partHeaders)) {
				return partBody;
			}
		}
		return null;
	}

	private static boolean isSdpPart(String partHeaders) {
		for (String line : partHeaders.split("\\r?\\n")) {
			String lower = line.trim().toLowerCase();
			if (lower.startsWith("content-type:") && lower.contains("application/sdp")) {
				return true;
			}
		}
		return false;
	}

	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0)
			return idx2;
		if (idx2 < 0)
			return idx;
		return Math.min(idx, idx2);
	}

	// ------------------------------------------------------------
	// B2buaListener (other callbacks unused)
	// ------------------------------------------------------------

	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse response) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException {
	}
}
