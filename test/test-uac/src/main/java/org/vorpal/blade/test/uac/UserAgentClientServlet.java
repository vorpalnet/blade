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
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
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
	///   - `multipart/*` body → rebuild the body as a multipart whose
	///     first part is the SDP (from the template if it has one, else
	///     from the softphone) followed by the template's non-SDP parts
	///     (e.g. SIPREC `application/rs-metadata+xml`)
	///   - any other body → leave existing content alone
	///
	/// **SDP precedence**: a template's `application/sdp` part — present
	/// in SIPREC samples whose `a=label:…` lines must match the metadata
	/// — overrides the softphone's SDP. Templates without an SDP part
	/// fall back to the softphone's SDP so non-SIPREC flows still
	/// negotiate media against the softphone end-to-end.
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

				if ("Contact".equalsIgnoreCase(name)) {
					// `Contact` is a SIP system header — `setHeader` throws.
					// The container manages the Contact URI; what the template
					// can usefully contribute is header parameters (notably
					// `+sip.src` for SIPREC role advertisement). Parse the
					// template Contact, copy its parameters onto the outbound.
					applyContactParameters(request, value);
					continue;
				}

				// Per-header try/catch so a single rejected header (e.g. another
				// system header someone added to a template) doesn't abort the
				// rest of the template, including the multipart-body handling
				// further down.
				try {
					request.setHeader(name, value);
				} catch (Exception e) {
					sipLogger.warning(request, "UserAgentClientServlet template '" + filename
							+ "' skipping header '" + name + "': " + e.getMessage());
				}
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

	/// Parse a template `Contact` header value and copy its header
	/// parameters onto the outbound request's container-managed Contact
	/// (which `setHeader` would reject as a system header). The template
	/// Contact URI is discarded — only the parameters survive, since
	/// the container owns the host:port and contact-URI shape. The
	/// SIPREC `+sip.src` parameter is the load-bearing one for our
	/// tests; `transport`, custom params, etc. ride along.
	private void applyContactParameters(SipServletRequest request, String templateContactValue) {
		try {
			Parameterable templateContact = sipFactory.createParameterable(templateContactValue);
			Address outboundContact = request.getAddressHeader("Contact");
			if (outboundContact == null) {
				sipLogger.warning(request, "Contact header missing on outbound; cannot apply template parameters");
				return;
			}
			java.util.Iterator<String> names = templateContact.getParameterNames();
			while (names.hasNext()) {
				String pname = names.next();
				String pvalue = templateContact.getParameter(pname);
				outboundContact.setParameter(pname, pvalue == null ? "" : pvalue);
			}
		} catch (Exception e) {
			sipLogger.warning(request, "failed to apply template Contact parameters: " + e.getMessage());
		}
	}

	/// Rebuild the outbound request body as a multipart whose first part
	/// is the SDP and whose subsequent parts are the template's non-SDP
	/// parts. SDP precedence: if the template carries its own
	/// `application/sdp` part (e.g. a SIPREC sample with `a=label:…`
	/// that has to line up with the metadata) it wins; otherwise the
	/// softphone's existing SDP is preserved.
	private void wrapSdpInMultipart(SipServletRequest request, String newContentType,
			String templateBody) {
		String boundary = extractBoundary(newContentType);
		if (boundary == null) {
			sipLogger.warning(request, "multipart template Content-Type missing boundary: "
					+ newContentType);
			return;
		}

		List<MultipartPart> templateParts = parseMultipartParts(templateBody, boundary);

		// SDP source: template's SDP if present (SIPREC case where labels
		// must match the metadata), otherwise the softphone's SDP.
		String sdp = null;
		String sdpPartHeaders = "Content-Type: application/sdp";
		for (MultipartPart p : templateParts) {
			if (p.isSdp) {
				sdp = p.body;
				sdpPartHeaders = p.headers;
				break;
			}
		}
		if (sdp == null) {
			try {
				sdp = extractExistingSdp(request);
			} catch (Exception e) {
				sipLogger.warning(request, "failed to read existing content: " + e.getMessage());
				return;
			}
			if (sdp == null) {
				sipLogger.warning(request, "no SDP in template or request; leaving body alone");
				return;
			}
		}

		StringBuilder out = new StringBuilder();
		// 1. SDP part, first
		out.append("--").append(boundary).append("\r\n");
		out.append(sdpPartHeaders);
		if (!endsWithNewline(sdpPartHeaders)) out.append("\r\n");
		out.append("\r\n");
		out.append(sdp);
		if (!endsWithNewline(sdp)) out.append("\r\n");

		// 2. Template's non-SDP parts, in their declared order
		for (MultipartPart p : templateParts) {
			if (p.isSdp) continue;
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
		// If the body starts at the first boundary directly (no preamble), prepend
		// a CRLF so the split regex `\r?\n<sep>` matches the leading boundary too;
		// otherwise the first part lands in segments[0] and gets skipped as preamble.
		String working = body.startsWith(separator) ? "\r\n" + body : body;
		String[] segments = working.split("\\r?\\n" + Pattern.quote(separator));

		// segments[0] is the preamble before the first boundary (empty when the
		// body starts at the boundary, since we prepended CRLF above).
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
