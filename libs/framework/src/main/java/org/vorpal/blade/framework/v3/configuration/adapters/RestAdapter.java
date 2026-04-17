package org.vorpal.blade.framework.v3.configuration.adapters;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// HTTP/REST adapter. Asynchronously calls a remote API (`url`,
/// optionally with a `bodyTemplate` for POST), then passes the
/// response body to each
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector]
/// — typically a `JsonSelector` or `XmlSelector`.
///
/// Auth options (`bearerToken`, `basicAuth`) and the URL/body
/// template all support `${var}` substitution from session state
/// — so values extracted by an upstream `SipAdapter` or
/// `MapAdapter` (e.g. `customerId`, `apiKey`) flow into the call.
///
/// ## Asynchronous
///
/// Uses [HttpClient#sendAsync] so the SIP container thread is
/// released immediately. When the HTTP response arrives (on the
/// shared HttpClient executor), selectors run and the returned
/// future completes. The iRouter's adapter chain then proceeds to
/// the next adapter (or the routing decision).
@JsonPropertyOrder({ "type", "id", "description", "url", "method", "basicAuth", "bearerToken",
		"timeoutSeconds", "bodyTemplate", "selectors" })
public class RestAdapter extends Adapter implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String url;
	protected String method = "GET";
	protected String basicAuth;
	protected String bearerToken;
	protected Integer timeoutSeconds = 5;
	protected String bodyTemplate;

	@JsonIgnore
	private transient HttpClient httpClient;
	@JsonIgnore
	private transient String cachedTemplate;

	public RestAdapter() {
	}

	@JsonPropertyDescription("URL template; supports ${var}")
	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }

	@JsonPropertyDescription("HTTP method: GET or POST (default GET)")
	public String getMethod() { return method; }
	public void setMethod(String method) { this.method = method; }

	@JsonPropertyDescription("user:password Basic auth credentials; supports ${var}")
	public String getBasicAuth() { return basicAuth; }
	public void setBasicAuth(String basicAuth) { this.basicAuth = basicAuth; }

	@JsonPropertyDescription("Bearer token for the Authorization header; supports ${var}")
	public String getBearerToken() { return bearerToken; }
	public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }

	@JsonPropertyDescription("Request timeout in seconds (default 5)")
	public Integer getTimeoutSeconds() { return timeoutSeconds; }
	public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

	@JsonPropertyDescription("Filename in _templates/ — HTTP-message format (headers + blank line + body)")
	public String getBodyTemplate() { return bodyTemplate; }
	public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (url == null) return CompletableFuture.completedFuture(null);

		Logger sipLogger = SettingsManager.getSipLogger();
		final String adapterId = id;

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

			if (bearerToken != null) {
				reqBuilder.header("Authorization", "Bearer " + ctx.resolve(bearerToken));
			} else if (basicAuth != null) {
				String resolvedAuth = ctx.resolve(basicAuth);
				String encoded = Base64.getEncoder().encodeToString(resolvedAuth.getBytes());
				reqBuilder.header("Authorization", "Basic " + encoded);
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
				sipLogger.finer("RestAdapter[" + adapterId + "] " + method + " " + resolvedUrl);
			}

			return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
					.thenAccept(httpResp -> {
						try {
							if (httpResp.statusCode() < 200 || httpResp.statusCode() >= 300) {
								sipLogger.warning("RestAdapter[" + adapterId + "] HTTP "
										+ httpResp.statusCode());
								return;
							}
							runSelectors(ctx, httpResp.body());
						} catch (Exception e) {
							sipLogger.warning("RestAdapter[" + adapterId + "] response handling failed: "
									+ e.getMessage());
						}
					})
					.exceptionally(t -> {
						sipLogger.warning("RestAdapter[" + adapterId + "] request failed: "
								+ t.getMessage());
						return null;
					});

		} catch (Exception e) {
			sipLogger.warning("RestAdapter[" + adapterId + "] failed to build request: "
					+ e.getMessage());
			return CompletableFuture.completedFuture(null);
		}
	}

	// ---- template loading helpers ----

	private static class TemplateResult {
		Map<String, String> headers = new LinkedHashMap<>();
		String body = "";
	}

	private TemplateResult loadAndResolveTemplate(String filename, Context ctx) throws IOException {
		if (cachedTemplate == null) {
			Path p = Paths.get(TEMPLATES_DIR, filename);
			if (!Files.exists(p)) {
				throw new IOException("Template not found: " + p);
			}
			cachedTemplate = Files.readString(p);
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
