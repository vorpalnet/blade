package org.vorpal.blade.test.uas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.test.uas.callflows.TestInvite;
import org.vorpal.blade.test.uas.callflows.TestNotImplemented;
import org.vorpal.blade.test.uas.callflows.TestOkayResponse;
import org.vorpal.blade.test.uas.callflows.TestReinvite;
import org.vorpal.blade.test.uas.config.TestUasConfig;
import org.vorpal.blade.test.uas.config.TestUasConfigSample;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UasServlet extends B2buaServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	public static SettingsManager<TestUasConfig> settingsManager;

	@Resource
	private SipFactory sipFactory;

	private String cachedTemplateName;
	private String cachedTemplate;

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		switch (request.getMethod()) {
		case "INVITE":
			callflow = request.isInitial() ? new TestInvite() : new TestReinvite();
			break;
		case "CANCEL":
		case "INFO":
		case "BYE":
			callflow = new TestOkayResponse();
			break;
		default:
			callflow = new TestNotImplemented();
		}

		return callflow;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
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

		String template = settingsManager.getCurrent().getTemplate();
		if (template != null && !template.isEmpty()) {
			applyTemplate(outboundRequest, template);
		}
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
			if (content == null) return;
			String body = (content instanceof String)
					? (String) content
					: new String((byte[]) content);

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
		if (idx < 0) return null;
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
		String[] parts = body.split("\\r?\\n" + Pattern.quote(separator));
		// parts[0] is the preamble before the first boundary.
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];

			// Remove CRLF right after the boundary marker.
			if (part.startsWith("\r\n")) part = part.substring(2);
			else if (part.startsWith("\n")) part = part.substring(1);

			// End boundary: "--boundary--" → the leading "--" is on
			// the part here, indicating we're past the last part.
			if (part.startsWith("--")) break;

			int blank = findBlankLine(part);
			if (blank < 0) continue;

			String partHeaders = part.substring(0, blank);
			String partBody = part.substring(blank).trim();

			// Trim trailing CRLF that precedes the next boundary.
			if (partBody.endsWith("\r\n")) partBody = partBody.substring(0, partBody.length() - 2);

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

	// ------------------------------------------------------------
	// Template application (same shape as test-uac)
	// ------------------------------------------------------------

	private void applyTemplate(SipServletRequest request, String filename) {
		try {
			String raw = loadTemplate(filename);
			int blank = findBlankLine(raw);
			String headerSection = (blank >= 0) ? raw.substring(0, blank) : raw;
			String body = (blank >= 0) ? raw.substring(blank).trim() : "";

			String contentType = null;
			boolean firstLine = true;

			for (String line : headerSection.split("\\r?\\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;

				if (firstLine) {
					firstLine = false;
					if (isRequestLine(line)) {
						String uri = requestLineUri(line);
						if (uri != null) {
							request.setRequestURI(sipFactory.createURI(uri));
						}
						continue;
					}
				}

				int colon = line.indexOf(':');
				if (colon <= 0) continue;
				String name = line.substring(0, colon).trim();
				String value = line.substring(colon + 1).trim();

				if ("Content-Type".equalsIgnoreCase(name)) {
					contentType = value;
					continue;
				}

				request.setHeader(name, value);
			}

			if (!body.isEmpty()) {
				if (contentType == null) contentType = "application/sdp";
				request.setContent(body, contentType);
			}
		} catch (Exception e) {
			sipLogger.warning(request, "UasServlet template '" + filename + "' failed: " + e.getMessage());
		}
	}

	private String loadTemplate(String filename) throws IOException {
		if (cachedTemplate == null || !filename.equals(cachedTemplateName)) {
			Path p = Paths.get(TEMPLATES_DIR, filename);
			if (!Files.exists(p)) {
				throw new IOException("Template not found: " + p);
			}
			cachedTemplate = Files.readString(p);
			cachedTemplateName = filename;
		}
		return cachedTemplate;
	}

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

	public void invalidateTemplateCache() {
		cachedTemplate = null;
		cachedTemplateName = null;
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
