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

	@JsonPropertyDescription("Filename in _templates/ — HTTP-message format (headers + blank line + body)")
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
				authentication.applyTo(reqBuilder, ctx);
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

			return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
					.thenAccept(httpResp -> {
						try {
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
				throw new IOException("Template not found: " + p);
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

	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0) return idx2;
		if (idx2 < 0) return idx;
		return Math.min(idx, idx2);
	}
}
