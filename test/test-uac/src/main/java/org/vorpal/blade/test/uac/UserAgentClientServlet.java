package org.vorpal.blade.test.uac;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UserAgentClientServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	public static SettingsManager<UserAgentClientConfig> settingsManager;

	@Resource
	private SipFactory sipFactory;

	// Cached raw template, keyed by filename. Re-read if the config
	// points at a different file.
	private String cachedTemplateName;
	private String cachedTemplate;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		settingsManager = new SettingsManager<>(event, UserAgentClientConfig.class, new UserAgentClientConfigSample());
		sipLogger.info("UserAgentClientServlet.servletCreated");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		sipLogger.info("UserAgentClientServlet.servletDestroyed");
		Object gen = event.getServletContext().getAttribute("loadGenerator");
		if (gen instanceof LoadGenerator) {
			((LoadGenerator) gen).stop();
		}
		settingsManager.unregister();
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "UserAgentClientServlet.callStarted");

		outboundRequest.setAttribute("noKeepAlive", Boolean.TRUE);

		String template = settingsManager.getCurrent().getTemplate();
		if (template != null && !template.isEmpty()) {
			applyTemplate(outboundRequest, template);
		}
	}

	/// Load the template file, parse it as a SIP message, and apply
	/// it to the outbound request:
	///
	/// - request line (if present) → `setRequestURI`
	/// - `Name: value` headers → `setHeader`
	/// - body handling depends on template Content-Type:
	///   - empty body → leave existing content alone
	///   - `multipart/*` body → preserve the existing SDP and wrap
	///     it with the template's non-SDP parts (e.g. SIPREC XML)
	///     into a new multipart body
	///   - any other body → leave existing content alone
	///
	/// **The outbound SDP (from the softphone) is never overwritten.**
	/// A template can *add* non-SDP attachments via multipart, but
	/// the SDP is preserved verbatim so downstream endpoints can
	/// establish media with the softphone.
	private void applyTemplate(SipServletRequest request, String filename) {
		try {
			String raw = loadTemplate(filename);
			int blank = findBlankLine(raw);
			String headerSection = (blank >= 0) ? raw.substring(0, blank) : raw;
			String body = (blank >= 0) ? raw.substring(blank).trim() : "";

			String templateContentType = null;
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
					templateContentType = value;
					continue;
				}

				request.setHeader(name, value);
			}

			// Body handling — SDP is sacred.
			if (!body.isEmpty() && templateContentType != null
					&& templateContentType.toLowerCase().startsWith("multipart/")) {
				wrapSdpInMultipart(request, templateContentType, body);
			}
			// else: leave request body alone (preserve SDP).

		} catch (Exception e) {
			sipLogger.warning(request, "UserAgentClientServlet template '" + filename
					+ "' failed: " + e.getMessage());
		}
	}

	/// Rebuild the outbound request body as a multipart message whose
	/// first part is the original SDP (extracted from the existing
	/// content) and whose subsequent parts come from the template.
	/// Any `application/sdp` parts in the template are dropped —
	/// the preserved SDP always wins.
	private void wrapSdpInMultipart(SipServletRequest request, String newContentType,
			String templateBody) {
		String boundary = extractBoundary(newContentType);
		if (boundary == null) {
			sipLogger.warning(request, "multipart template Content-Type missing boundary: "
					+ newContentType);
			return;
		}

		String existingSdp;
		try {
			existingSdp = extractExistingSdp(request);
		} catch (Exception e) {
			sipLogger.warning(request, "failed to read existing content: " + e.getMessage());
			return;
		}
		if (existingSdp == null) {
			sipLogger.warning(request, "no SDP in request; leaving body alone");
			return;
		}

		List<MultipartPart> templateParts = parseMultipartParts(templateBody, boundary);

		StringBuilder out = new StringBuilder();
		// 1. Preserved SDP part, first
		out.append("--").append(boundary).append("\r\n");
		out.append("Content-Type: application/sdp\r\n\r\n");
		out.append(existingSdp);
		if (!endsWithNewline(existingSdp)) out.append("\r\n");

		// 2. Template's non-SDP parts, in their declared order
		for (MultipartPart p : templateParts) {
			if (p.isSdp) continue; // the preserved SDP already covers this
			out.append("--").append(boundary).append("\r\n");
			out.append(p.headers);
			if (!endsWithNewline(p.headers)) out.append("\r\n");
			out.append("\r\n");
			out.append(p.body);
			if (!endsWithNewline(p.body)) out.append("\r\n");
		}

		out.append("--").append(boundary).append("--\r\n");

		try {
			request.setContent(out.toString().getBytes(), newContentType);
		} catch (Exception e) {
			sipLogger.warning(request, "setContent failed: " + e.getMessage());
		}
	}

	/// Return the SDP currently carried in `request`, whether the
	/// content-type is `application/sdp` directly or a `multipart/*`
	/// that contains an SDP part.
	private static String extractExistingSdp(SipServletRequest request) throws IOException {
		Object content = request.getContent();
		if (content == null) return null;
		String body = (content instanceof String) ? (String) content : new String((byte[]) content);

		String ct = request.getContentType();
		if (ct == null) return null;
		String lower = ct.toLowerCase();

		if (lower.startsWith("multipart/")) {
			String b = extractBoundary(ct);
			if (b == null) return null;
			for (MultipartPart p : parseMultipartParts(body, b)) {
				if (p.isSdp) return p.body;
			}
			return null;
		}
		if (lower.contains("application/sdp")) {
			return body;
		}
		return null;
	}

	// ------------------------------------------------------------
	// Multipart helpers
	// ------------------------------------------------------------

	private static class MultipartPart {
		final String headers;
		final String body;
		final boolean isSdp;

		MultipartPart(String headers, String body) {
			this.headers = headers;
			this.body = body;
			this.isSdp = headersDeclareSdp(headers);
		}

		private static boolean headersDeclareSdp(String headers) {
			for (String line : headers.split("\\r?\\n")) {
				String lower = line.trim().toLowerCase();
				if (lower.startsWith("content-type:") && lower.contains("application/sdp")) {
					return true;
				}
			}
			return false;
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

	/// Split a multipart body into its constituent parts on the
	/// declared boundary. Each part's headers are above a blank
	/// line, body below. Returns an empty list if nothing parses.
	private static List<MultipartPart> parseMultipartParts(String body, String boundary) {
		List<MultipartPart> out = new ArrayList<>();
		if (body == null || body.isEmpty() || boundary == null) return out;

		String separator = "--" + boundary;
		String[] segments = body.split("\\r?\\n" + Pattern.quote(separator));

		// segments[0] is the preamble before the first boundary.
		for (int i = 1; i < segments.length; i++) {
			String segment = segments[i];
			if (segment.startsWith("\r\n")) segment = segment.substring(2);
			else if (segment.startsWith("\n")) segment = segment.substring(1);

			// "--boundary--" closing marker → done
			if (segment.startsWith("--")) break;

			int blank = findBlankLine(segment);
			if (blank < 0) continue;

			String partHeaders = segment.substring(0, blank).trim();
			String partBody = segment.substring(blank).trim();
			out.add(new MultipartPart(partHeaders, partBody));
		}
		return out;
	}

	private static boolean endsWithNewline(String s) {
		return s.endsWith("\n") || s.endsWith("\r");
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

	/// True if the line looks like a SIP request line:
	/// `METHOD <URI> SIP/2.0`.
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

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		sipLogger.info(outboundResponse, "UserAgentClientServlet.callAnswered");
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "UserAgentClientServlet.callConnected");
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "UserAgentClientServlet.callCompleted");
		Object gen = outboundRequest.getApplicationSession().getAttribute("loadGenerator");
		if (gen instanceof LoadGenerator) {
			((LoadGenerator) gen).onCallCompleted();
		}
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		sipLogger.info(outboundResponse, "UserAgentClientServlet.callDeclined");
		Object gen = outboundResponse.getRequest().getApplicationSession().getAttribute("loadGenerator");
		if (gen instanceof LoadGenerator) {
			((LoadGenerator) gen).onCallFailed();
		}
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "UserAgentClientServlet.callAbandoned");
	}

	/// Exposed for testing — allows forcing a template reload without
	/// bouncing the servlet (e.g. after the operator edits the file).
	public void invalidateTemplateCache() {
		cachedTemplate = null;
		cachedTemplateName = null;
	}
}
