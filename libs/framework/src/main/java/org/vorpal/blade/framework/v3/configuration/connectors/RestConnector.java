package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.auth.Authentication;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// HTTP/REST connector. Asynchronously calls a remote API (`url`,
/// optionally with a `bodyTemplate` for POST), then passes the
/// response body to each
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector]
/// — typically a `JsonSelector` or `XmlSelector`.
///
/// The optional [#getAuthentication] field is a polymorphic
/// [Authentication] — static credentials (basic, bearer, apikey) or one
/// of five Nimbus-backed OAuth 2.0 grants (oauth2-password,
/// oauth2-client, oauth2-refresh-token, oauth2-jwt-bearer,
/// oauth2-saml-bearer). OAuth token caching + refresh are handled
/// inside the Authentication subtype.
///
/// URL, body template, and every auth field support `${var}`
/// substitution from session state — so values extracted by an upstream
/// `SipConnector` or `TableConnector` (e.g. `customerId`, `apiKey`)
/// flow directly into the call.
///
/// ## Body-template format
///
/// The file is HTTP-message-style: any number of `Name: Value`
/// header lines, a blank line, then the body. Lines whose first
/// non-whitespace character is `#` are treated as comments and
/// stripped at load time — use them to annotate the template
/// without ending up in the wire payload.
///
/// ## Body-template bootstrap (self-materializing)
///
/// At runtime templates are read from
/// `./config/custom/vorpal/_templates/<filename>` (relative to the
/// WLS server's working directory). If a template referenced by
/// [#getBodyTemplate] is missing on disk, the connector looks for a
/// bundled copy on the WAR's classpath at `_templates/<filename>` —
/// i.e. shipped via `src/main/resources/_templates/<filename>` in the
/// service module — and copies it to the disk path on the first
/// invocation. The parent directory is created if needed. After that,
/// reads come from disk normally.
///
/// **The bootstrap never overwrites an existing file.** Once the
/// template is on disk, operators can edit it (via the Configurator's
/// file editor or directly) and subsequent WAR redeploys will not
/// stomp those edits. To re-pull the WAR-bundled copy, delete the
/// disk file; the next call will re-materialize it.
///
/// **For module authors:** ship templates by placing them under
/// `src/main/resources/_templates/<filename>`. Maven copies them into
/// the WAR at `WEB-INF/classes/_templates/<filename>`; the WAR
/// classloader exposes them via `getResourceAsStream` and the
/// connector takes care of the rest. No deploy-time copy step is
/// required.
///
/// **For operators:** if the template ever changes shape between WAR
/// versions, delete the on-disk copy after upgrade so the new
/// bundled version takes its place. The startup log will record:
/// `RestConnector[<id>] bootstrapped template from WAR: <path>
/// (source: classpath:_templates/<filename>)`.
///
/// ## Asynchronous
///
/// Uses [HttpClient#sendAsync] so the SIP container thread is
/// released immediately. When the HTTP response arrives (on the
/// shared HttpClient executor), selectors run and the returned
/// future completes. The iRouter's connector chain then proceeds to
/// the next connector (or the routing decision).
@JsonPropertyOrder({ "type", "id", "description", "url", "method", "authentication",
		"timeoutSeconds", "bodyTemplate", "selectors" })
@FormLayoutGroup({ "id", "method", "timeoutSeconds" })
public class RestConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String url;
	protected String method = "GET";
	protected Authentication authentication;
	protected Integer timeoutSeconds = 5;
	protected String bodyTemplate;

	@JsonIgnore
	private transient HttpClient httpClient;
	@JsonIgnore
	private transient String cachedTemplate;

	public RestConnector() {
	}

	@JsonPropertyDescription("URL template; supports ${var}")
	@FormLayout(wide = true)
	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }

	@JsonPropertyDescription("HTTP method: GET or POST (default GET)")
	public String getMethod() { return method; }
	public void setMethod(String method) { this.method = method; }

	@JsonPropertyDescription("Authentication scheme; pick a type (basic / bearer / apikey / oauth2-password / oauth2-client / oauth2-refresh-token / oauth2-jwt-bearer / oauth2-saml-bearer)")
	public Authentication getAuthentication() { return authentication; }
	public void setAuthentication(Authentication authentication) { this.authentication = authentication; }

	@JsonPropertyDescription("Request timeout in seconds (default 5)")
	public Integer getTimeoutSeconds() { return timeoutSeconds; }
	public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

	@JsonPropertyDescription("Filename in _templates/ — HTTP-message format (headers + blank line + body). If the file is missing on disk, the WAR's bundled copy at classpath:_templates/<filename> is auto-materialized on first use; never overwrites an existing file.")
	@FormLayout(wide = true)
	public String getBodyTemplate() { return bodyTemplate; }
	public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (url == null) return CompletableFuture.completedFuture(null);

		Logger sipLogger = SettingsManager.getSipLogger();
		final String connectorId = id;

		try {
			String resolvedUrl = ctx.resolve(url);
			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
					.uri(URI.create(resolvedUrl))
					.timeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 5));

			Map<String, String> templateHeaders = null;
			String resolvedBody = null;

			if ("POST".equalsIgnoreCase(method) && bodyTemplate != null) {
				TemplateResult tpl = loadAndResolveTemplate(bodyTemplate, ctx);
				templateHeaders = tpl.headers;
				resolvedBody = tpl.body;
			}

			if ("POST".equalsIgnoreCase(method)) {
				if (resolvedBody != null) {
					reqBuilder.POST(HttpRequest.BodyPublishers.ofString(resolvedBody));
				} else {
					reqBuilder.POST(HttpRequest.BodyPublishers.ofString("{}"));
					reqBuilder.header("Content-Type", "application/json");
				}
			} else {
				reqBuilder.GET();
			}

			if (authentication != null) {
				authentication.applyTo(reqBuilder, ctx,
						new Authentication.RequestSignature(method, resolvedUrl, resolvedBody));
			}

			if (templateHeaders != null) {
				for (Map.Entry<String, String> e : templateHeaders.entrySet()) {
					reqBuilder.header(e.getKey(), e.getValue());
				}
			}

			if (httpClient == null) {
				httpClient = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 5))
						.build();
			}

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("RestConnector[" + connectorId + "] " + method + " " + resolvedUrl);
			}

			HttpRequest httpReq = reqBuilder.build();
			if (sipLogger.isLoggable(Level.FINEST)) {
				sipLogger.finest(formatHttpRequest(connectorId, method, resolvedUrl, httpReq, resolvedBody));
			}

			return httpClient.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
					.thenAccept(httpResp -> {
						try {
							if (sipLogger.isLoggable(Level.FINEST)) {
								sipLogger.finest(formatHttpResponse(connectorId, httpResp));
							}
							if (httpResp.statusCode() < 200 || httpResp.statusCode() >= 300) {
								sipLogger.warning("RestConnector[" + connectorId + "] HTTP "
										+ httpResp.statusCode());
								return;
							}
							runSelectors(ctx, httpResp.body());
						} catch (Exception e) {
							sipLogger.warning("RestConnector[" + connectorId + "] response handling failed: "
									+ e.getMessage());
						}
					})
					.exceptionally(t -> {
						sipLogger.warning("RestConnector[" + connectorId + "] request failed: "
								+ t.getMessage());
						return null;
					});

		} catch (Exception e) {
			sipLogger.warning("RestConnector[" + connectorId + "] failed to build request: "
					+ e.getMessage());
			return CompletableFuture.completedFuture(null);
		}
	}

	// ---- template loading helpers ----

	private static class TemplateResult {
		Map<String, String> headers = new LinkedHashMap<>();
		String body = "";
	}

	// Any line whose first non-whitespace character is `#` is a comment.
	// The whole line — including its terminator — is stripped at load time,
	// BEFORE the cached template is substituted, so `${…}` placeholders
	// inside a commented line never resolve (and can't accidentally leak
	// secrets into a debug rendering). Horizontal whitespace only (`[ \t]`)
	// so the regex can't swallow line separators.
	private static final java.util.regex.Pattern COMMENT_LINE =
			java.util.regex.Pattern.compile("(?m)^[ \\t]*#.*(?:\\r?\\n)?");

	private TemplateResult loadAndResolveTemplate(String filename, Context ctx) throws IOException {
		if (cachedTemplate == null) {
			Path p = Paths.get(TEMPLATES_DIR, filename);
			if (!Files.exists(p)) {
				// First-run bootstrap from the WAR (see class Javadoc).
				materializeBundledTemplate(filename, p);
				if (!Files.exists(p)) {
					throw new IOException("Template not found on disk and not bundled in WAR: " + p);
				}
			}
			cachedTemplate = COMMENT_LINE.matcher(Files.readString(p)).replaceAll("");
		}

		String resolved = ctx.resolve(cachedTemplate);
		TemplateResult result = new TemplateResult();
		int blank = findBlankLine(resolved);

		if (blank >= 0) {
			String headerSection = resolved.substring(0, blank).trim();
			result.body = resolved.substring(blank).trim();
			for (String line : headerSection.split("\\r?\\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				int colon = line.indexOf(':');
				if (colon > 0) {
					result.headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
				}
			}
		} else {
			result.body = resolved.trim();
		}
		return result;
	}

	/// Copy the WAR-bundled body template at
	/// `classpath:_templates/<filename>` to the on-disk location
	/// `destination`, creating parent directories as needed. Called
	/// only when the disk file is missing — never overwrites an
	/// existing file. Silently no-ops if no bundled copy is on the
	/// classpath; the caller will then surface a "template not found"
	/// error.
	///
	/// Uses the **thread context classloader** rather than this class's
	/// own loader: the framework JAR (where this class lives) is
	/// bundled inside each WAR, but `WEB-INF/classes/_templates/` is
	/// owned by the WAR-level WebappClassLoader. The context loader is
	/// the WAR's loader at SIP-container request time, so it can see
	/// both `WEB-INF/classes/` and `WEB-INF/lib/*.jar`. Falls back to
	/// `getClass().getClassLoader()` when no context loader is set
	/// (e.g. a static unit test).
	///
	/// Logs at INFO on successful bootstrap (one line per template
	/// per JVM lifetime, since the result is cached in
	/// `cachedTemplate` thereafter), at FINE when no bundled copy
	/// exists, and at WARNING on I/O failure.
	///
	/// @param filename     bare filename, e.g. `securelogix.txt`
	/// @param destination  absolute path under
	///                     `./config/custom/vorpal/_templates/`
	private void materializeBundledTemplate(String filename, Path destination) {
		Logger sipLogger = SettingsManager.getSipLogger();
		String resourcePath = "_templates/" + filename;
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl == null) cl = getClass().getClassLoader();
		try (java.io.InputStream in = cl.getResourceAsStream(resourcePath)) {
			if (in == null) {
				if (sipLogger != null && sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine("RestConnector[" + id + "] no bundled template at classpath:" + resourcePath);
				}
				return;
			}
			Files.createDirectories(destination.getParent());
			Files.copy(in, destination);
			if (sipLogger != null) {
				sipLogger.info("RestConnector[" + id + "] bootstrapped template from WAR: "
						+ destination + " (source: classpath:" + resourcePath + ")");
			}
		} catch (IOException e) {
			if (sipLogger != null) {
				sipLogger.warning("RestConnector[" + id + "] failed to materialize bundled template "
						+ resourcePath + " to " + destination + ": " + e.getMessage());
			}
		}
	}

	/// HTTP-message-style render of the outbound request for FINEST logs.
	/// Includes Authorization / api-key headers verbatim — FINEST is a
	/// debug-only level, so operators opting in have accepted that.
	private static String formatHttpRequest(String connectorId, String method,
			String resolvedUrl, HttpRequest httpReq, String body) {
		StringBuilder sb = new StringBuilder();
		sb.append("RestConnector[").append(connectorId).append("] HTTP request:\n");
		sb.append(method).append(' ').append(resolvedUrl).append('\n');
		httpReq.headers().map().forEach((k, vs) -> {
			for (String v : vs) sb.append(k).append(": ").append(v).append('\n');
		});
		sb.append('\n');
		if (body != null) sb.append(body);
		return sb.toString();
	}

	private static String formatHttpResponse(String connectorId, HttpResponse<String> httpResp) {
		StringBuilder sb = new StringBuilder();
		sb.append("RestConnector[").append(connectorId).append("] HTTP response:\n");
		sb.append("HTTP/").append(httpResp.version()).append(' ').append(httpResp.statusCode()).append('\n');
		httpResp.headers().map().forEach((k, vs) -> {
			for (String v : vs) sb.append(k).append(": ").append(v).append('\n');
		});
		sb.append('\n');
		if (httpResp.body() != null) sb.append(httpResp.body());
		return sb.toString();
	}

	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0) return idx2;
		if (idx2 < 0) return idx;
		return Math.min(idx, idx2);
	}
}
