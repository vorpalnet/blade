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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.fasterxml.jackson.core.io.JsonStringEncoder;

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
/// ## Values from the call are escaped
///
/// A template's placeholders are usually filled from the SIP message, which
/// the caller writes. In a JSON body, a value that lands inside a string is
/// JSON-escaped, so a display name holding a quote cannot close the string and
/// add a field; a placeholder outside a string is inserted as written, for
/// values that are JSON already. A header value has its line breaks folded,
/// so it cannot add a header. The URL is not encoded: build it from
/// configuration values, not from caller text.
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
@JsonPropertyOrder({ "type", "id", "description", "url", "method", "authentication", "tls",
		"timeoutSeconds", "circuitBreakerCooldownSeconds", "circuitBreakerTrap", "bodyTemplate", "selectors" })
@FormLayoutGroup({ "id", "method", "timeoutSeconds" })
@FormLayoutGroup({ "circuitBreakerCooldownSeconds", "circuitBreakerTrap" })
public class RestConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String url;
	protected String method = "GET";
	protected Authentication authentication;
	protected org.vorpal.blade.framework.v3.security.TlsClientConfig tls;
	protected Integer timeoutSeconds = 5;
	protected Integer circuitBreakerCooldownSeconds;
	protected Boolean circuitBreakerTrap;
	protected String bodyTemplate;

	@JsonIgnore
	private transient HttpClient httpClient;
	@JsonIgnore
	private transient String cachedTemplate;

	@JsonIgnore
	private transient CircuitBreaker breaker = new CircuitBreaker();

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

	@JsonPropertyDescription("Optional TLS overrides for this endpoint: a private truststore and/or a client certificate for mutual TLS. Leave unset to use the JVM default truststore (the normal case).")
	public org.vorpal.blade.framework.v3.security.TlsClientConfig getTls() { return tls; }
	public void setTls(org.vorpal.blade.framework.v3.security.TlsClientConfig tls) { this.tls = tls; }

	@JsonPropertyDescription("Request timeout in seconds (default 5)")
	public Integer getTimeoutSeconds() { return timeoutSeconds; }
	public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

	@JsonPropertyDescription("Circuit breaker: after a failed call (transport error, timeout, or non-2xx), "
			+ "suppress further calls to this endpoint for N seconds and let routing fall to its default route. "
			+ "Prevents hammering a down endpoint and stops every call from eating the request timeout during an "
			+ "outage. 0 or empty disables it (default).")
	public Integer getCircuitBreakerCooldownSeconds() { return circuitBreakerCooldownSeconds; }
	public void setCircuitBreakerCooldownSeconds(Integer circuitBreakerCooldownSeconds) {
		this.circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds;
	}

	@JsonPropertyDescription("When the circuit breaker opens or recovers, emit one SNMP trap on each edge "
			+ "(down, then up). Requires the WebLogic SNMP agent enabled with a trap destination (see the Tuning "
			+ "app). Default false.")
	public Boolean getCircuitBreakerTrap() { return circuitBreakerTrap; }
	public void setCircuitBreakerTrap(Boolean circuitBreakerTrap) { this.circuitBreakerTrap = circuitBreakerTrap; }

	@JsonPropertyDescription("Filename in _templates/ — HTTP-message format (headers + blank line + body). If the file is missing on disk, the WAR's bundled copy at classpath:_templates/<filename> is auto-materialized on first use; never overwrites an existing file.")
	@FormLayout(wide = true)
	public String getBodyTemplate() { return bodyTemplate; }
	public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (url == null) return CompletableFuture.completedFuture(null);

		Logger sipLogger = SettingsManager.getSipLogger();
		final String connectorId = id;

		final boolean breakerEnabled = circuitBreakerCooldownSeconds != null && circuitBreakerCooldownSeconds > 0;
		if (breakerEnabled && breaker.isOpen()) {
			// OPEN — skip the call. Selectors don't run, so this connector
			// contributes nothing to the Context and the iRouter's routing falls
			// to its default route (fail-open or fail-closed is the routing
			// config's call, not ours). Cheap and immediate: no per-call timeout
			// wait while the endpoint is known-down.
			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(ctx.getRequest(), tag() + " circuit open — skipping call, routing to default");
			}
			return CompletableFuture.completedFuture(null);
		}

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

			// Build the TLS context first (throws on a misconfigured store —
			// fail closed rather than silently downgrading to default trust)
			// and share it with the auth scheme so its token fetch uses the
			// same trust/client identity as the API call.
			javax.net.ssl.SSLContext sslContext = null;
			if (tls != null && !tls.isEmpty()) {
				sslContext = tls.buildSslContext();
			}

			if (authentication != null) {
				authentication.setSslContext(sslContext);
				authentication.applyTo(reqBuilder, ctx,
						new Authentication.RequestSignature(method, resolvedUrl, resolvedBody));
			}

			if (templateHeaders != null) {
				for (Map.Entry<String, String> e : templateHeaders.entrySet()) {
					reqBuilder.header(e.getKey(), e.getValue());
				}
			}

			if (httpClient == null) {
				HttpClient.Builder clientBuilder = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 5));
				if (sslContext != null) {
					clientBuilder.sslContext(sslContext);
				}
				httpClient = clientBuilder.build();
			}

			javax.servlet.sip.SipServletRequest sipReq = ctx.getRequest();
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(sipReq, "RestConnector[" + connectorId + "] " + method + " " + resolvedUrl);
			}

			HttpRequest httpReq = reqBuilder.build();
			if (sipLogger.isLoggable(Level.FINEST)) {
				sipLogger.finest(sipReq, formatHttpRequest(connectorId, method, resolvedUrl, httpReq, resolvedBody));
			}

			return httpClient.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
					.thenAccept(httpResp -> {
						try {
							if (sipLogger.isLoggable(Level.FINEST)) {
								sipLogger.finest(sipReq, formatHttpResponse(connectorId, httpResp));
							}
							if (httpResp.statusCode() < 200 || httpResp.statusCode() >= 300) {
								sipLogger.warning(sipReq, "RestConnector[" + connectorId + "] HTTP "
										+ httpResp.statusCode());
								if (breakerEnabled) breaker.recordFailure(circuitBreakerCooldownSeconds,
										Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag(),
										"HTTP " + httpResp.statusCode());
								return;
							}
							if (breakerEnabled) breaker.recordSuccess(Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag());
							runSelectors(ctx, httpResp.body());
						} catch (Exception e) {
							sipLogger.warning(sipReq, "RestConnector[" + connectorId + "] response handling failed: "
									+ e.getMessage());
						}
					})
					.exceptionally(t -> {
						sipLogger.warning(sipReq, "RestConnector[" + connectorId + "] request failed: "
								+ t.getMessage());
						if (breakerEnabled) breaker.recordFailure(circuitBreakerCooldownSeconds,
								Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag(), t.getMessage());
						return null;
					});

		} catch (Exception e) {
			sipLogger.warning(ctx != null ? ctx.getRequest() : null,
					"RestConnector[" + connectorId + "] failed to build request: " + e.getMessage());
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

		// Split the template before resolving anything, so a value from the call
		// holding a line break can neither move the header/body boundary nor
		// add a header line of its own.
		TemplateResult result = new TemplateResult();
		int blank = findBlankLine(cachedTemplate);
		String bodyTemplate = ((blank >= 0) ? cachedTemplate.substring(blank) : cachedTemplate).trim();

		if (blank >= 0) {
			for (String line : cachedTemplate.substring(0, blank).split("\\r?\\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				int colon = line.indexOf(':');
				if (colon > 0) {
					result.headers.put(line.substring(0, colon).trim(),
							stripLineBreaks(ctx.resolve(line.substring(colon + 1).trim())));
				}
			}
		}
		result.body = isJson(result.headers, bodyTemplate)
				? resolveJson(bodyTemplate, ctx)
				: ctx.resolve(bodyTemplate);
		return result;
	}

	/// A header value cannot contain a line break; one from the call is folded
	/// to a space rather than failing the request.
	static String stripLineBreaks(String value) {
		return value.replaceAll("[\\r\\n]+", " ");
	}

	/// A JSON body is one whose template declares a JSON `Content-Type`, or
	/// declares none and starts with `{` or `[`.
	static boolean isJson(Map<String, String> headers, String bodyTemplate) {
		for (Map.Entry<String, String> h : headers.entrySet()) {
			if ("content-type".equalsIgnoreCase(h.getKey())) {
				return h.getValue().toLowerCase(Locale.ROOT).contains("json");
			}
		}
		return bodyTemplate.startsWith("{") || bodyTemplate.startsWith("[");
	}

	/// Resolves a JSON body template, escaping each value that lands inside a
	/// JSON string. Values come from the call, and a display name holding
	/// `", "admin": true, "x": "` would otherwise close the string and add a
	/// field. A placeholder outside a string, such as `"strategy": ${strategy}`
	/// or a pre-built object like `${sipJson}`, is inserted as written: the
	/// template author put it there to be JSON, so it must only ever hold
	/// values the configuration or the application built, not raw caller text.
	static String resolveJson(String template, Context ctx) {
		Set<Integer> inString = placeholdersInStrings(template);
		JsonStringEncoder encoder = JsonStringEncoder.getInstance();
		return ctx.resolve(template,
				(offset, value) -> inString.contains(offset) ? new String(encoder.quoteAsString(value)) : value);
	}

	/// Offsets of every `${` that falls inside a JSON string literal.
	private static Set<Integer> placeholdersInStrings(String template) {
		Set<Integer> offsets = new HashSet<>();
		boolean inString = false;
		for (int i = 0; i < template.length(); i++) {
			char c = template.charAt(i);
			if (inString && c == '\\') {
				i++;
			} else if (c == '"') {
				inString = !inString;
			} else if (inString && c == '$' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
				offsets.add(i);
			}
		}
		return offsets;
	}

	/// HTTP-message-style render of the outbound request for FINEST logs, with
	/// credential headers masked. Log files are read by the logs app and by
	/// anyone with file access, and a live bearer token, API key or signature in
	/// them is as good as the credential itself.
	private String formatHttpRequest(String connectorId, String method,
			String resolvedUrl, HttpRequest httpReq, String body) {
		StringBuilder sb = new StringBuilder();
		sb.append("RestConnector[").append(connectorId).append("] HTTP request:\n");
		sb.append(method).append(' ').append(resolvedUrl).append('\n');
		httpReq.headers().map().forEach((k, vs) -> {
			for (String v : vs) {
				sb.append(k).append(": ").append(isCredentialHeader(k, authentication) ? "********" : v).append('\n');
			}
		});
		sb.append('\n');
		if (body != null) sb.append(body);
		return sb.toString();
	}

	/// A header that carries a credential: the one an API-key or HMAC scheme
	/// stamps, or any whose name says authorization, key, token, secret or
	/// signature (`Authorization`, `X-API-Key`, `X-Amz-Security-Token`, ...).
	static boolean isCredentialHeader(String name, Authentication authentication) {
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("auth") || lower.contains("key") || lower.contains("token")
				|| lower.contains("secret") || lower.contains("signature") || lower.contains("cookie")) {
			return true;
		}
		String schemeHeader = null;
		if (authentication instanceof org.vorpal.blade.framework.v3.configuration.auth.ApiKeyAuthentication) {
			schemeHeader = ((org.vorpal.blade.framework.v3.configuration.auth.ApiKeyAuthentication) authentication).getHeader();
		} else if (authentication instanceof org.vorpal.blade.framework.v3.configuration.auth.HmacAuthentication) {
			schemeHeader = ((org.vorpal.blade.framework.v3.configuration.auth.HmacAuthentication) authentication).getHeader();
		}
		return schemeHeader != null && schemeHeader.equalsIgnoreCase(name);
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
