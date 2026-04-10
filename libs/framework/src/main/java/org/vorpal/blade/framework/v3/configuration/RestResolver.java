package org.vorpal.blade.framework.v3.configuration;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// REST-based [Resolver] that queries an HTTP API to resolve a routing key.
///
/// ## GET mode
///
/// For simple lookups, the routing key and session attributes are
/// substituted directly into the URL template:
///
/// ```json
/// {
///   "type": "rest",
///   "id": "customer-api",
///   "url": "https://api.example.com/routing/${user}?src=${callingNumber}",
///   "method": "GET",
///   "bearerToken": "eyJ...",
///   "timeoutSeconds": 5,
///   "responseSelector": {
///     "id": "extract-uri",
///     "attribute": "$.route.destination",
///     "pattern": "(?<uri>.*)",
///     "expression": "${uri}"
///   }
/// }
/// ```
///
/// ## POST mode with body template
///
/// For complex requests, the POST body and additional headers come from
/// an external template file in the `_templates/` directory under the
/// BLADE config path (`<domain>/config/custom/vorpal/_templates/`).
///
/// The template uses **HTTP message format**: headers above a blank
/// line, JSON body below. All `${var}` placeholders in both sections
/// are resolved from session attributes collected by prior [Selector]s.
///
/// ```json
/// {
///   "type": "rest",
///   "id": "customer-api",
///   "url": "https://api.example.com/routing",
///   "method": "POST",
///   "bodyTemplate": "customer-lookup.txt",
///   "timeoutSeconds": 3,
///   "responseSelector": { ... }
/// }
/// ```
///
/// ### Template file format
///
/// The template file (`_templates/customer-lookup.txt`) uses a blank
/// line to separate headers from the JSON body. Both sections support
/// `${var}` substitution:
///
/// ```
/// Content-Type: application/json
/// X-Correlation-ID: ${key}
/// X-Source-App: irouter
///
/// {
///   "query": {
///     "calledNumber": "${user}",
///     "callingNumber": "${callingNumber}",
///     "sourceHost": "${host}"
///   },
///   "options": {
///     "includeFailover": true,
///     "maxResults": 1
///   }
/// }
/// ```
///
/// This format avoids the problem of embedding JSON-with-placeholders
/// inside a JSON config file (which would break schema validation).
/// The template is plain text — not validated as JSON until after
/// variable substitution.
///
/// ### Header precedence
///
/// Headers from three sources are merged in this order (later wins):
///
/// 1. Config-level auth (`basicAuth` or `bearerToken`)
/// 2. Template file headers (above the blank line)
/// 3. (Template headers override config-level auth if they set
///    `Authorization`)
///
/// ## Response handling
///
/// The JSON response from the API is processed in one of two ways:
///
/// - **With `responseSelector`**: The [Selector.findKey(JsonNode)]
///   method extracts a routing key from the response using JsonPath.
///   The extracted key becomes the `requestUri` in the returned
///   [Translation]'s treatment. Named groups from the selector become
///   additional headers.
///
/// - **Without `responseSelector`**: The entire JSON response is
///   deserialized as the treatment payload. Use this when the API
///   returns a structure that directly matches the treatment type.
///
/// ## Error handling
///
/// - HTTP errors (non-2xx status) return `null` (no match).
/// - Network errors and timeouts propagate as exceptions, caught by
///   [RouterConfiguration.findTranslation] and logged as warnings.
/// - The template file is cached after first load for performance.
/// - A missing template file throws [IOException] at resolve time.
///
/// ## Available `${var}` placeholders
///
/// All placeholders are resolved from the session attributes map
/// passed by [RouterConfiguration.findTranslation]:
///
/// - `${key}` — the routing key (always available)
/// - `${user}`, `${host}`, etc. — named capturing groups from [Selector]
///   patterns that ran before this resolver
/// - Any attribute set on the `SipApplicationSession` by prior selectors
///
/// @param <T> the treatment type (typically `RoutingTreatment`)
@JsonPropertyOrder({ "id", "description", "url", "method",
		"basicAuth", "bearerToken", "timeoutSeconds", "bodyTemplate", "responseSelector" })
public class RestResolver<T> implements Resolver<T>, Serializable {
	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(RestResolver.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	private String id;
	private String description;

	@JsonPropertyDescription("URL template with ${var} placeholders, e.g. https://api.example.com/route/${user}")
	private String url;

	@JsonPropertyDescription("HTTP method: GET or POST (default: GET)")
	private String method = "GET";

	@JsonPropertyDescription("Basic auth credentials in user:password format (supports ${var})")
	private String basicAuth;

	@JsonPropertyDescription("Bearer token for Authorization header (supports ${var})")
	private String bearerToken;

	@JsonPropertyDescription("Request timeout in seconds (default: 5)")
	private Integer timeoutSeconds = 5;

	@JsonPropertyDescription("Template filename in _templates/ directory for POST body and headers")
	private String bodyTemplate;

	@JsonPropertyDescription("Selector that extracts the destination from the JSON response via JsonPath")
	private Selector responseSelector;

	@JsonIgnore
	private transient HttpClient httpClient;

	@JsonIgnore
	private transient String cachedTemplate;

	/// Default constructor for JSON deserialization.
	public RestResolver() {
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public void setDescription(String description) {
		this.description = description;
	}

	/// Returns the URL template. Supports `${var}` placeholders that
	/// are resolved from session attributes at resolve time.
	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	/// Returns the HTTP method (`GET` or `POST`).
	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	/// Returns the basic auth credentials (`user:password` format).
	/// Supports `${var}` placeholders.
	public String getBasicAuth() {
		return basicAuth;
	}

	public void setBasicAuth(String basicAuth) {
		this.basicAuth = basicAuth;
	}

	/// Returns the bearer token for the `Authorization` header.
	/// Supports `${var}` placeholders.
	public String getBearerToken() {
		return bearerToken;
	}

	public void setBearerToken(String bearerToken) {
		this.bearerToken = bearerToken;
	}

	/// Returns the request timeout in seconds.
	public Integer getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public void setTimeoutSeconds(Integer timeoutSeconds) {
		this.timeoutSeconds = timeoutSeconds;
	}

	/// Returns the template filename for POST requests. The file is
	/// loaded from the `_templates/` directory under the BLADE config
	/// path (`<domain>/config/custom/vorpal/_templates/`).
	public String getBodyTemplate() {
		return bodyTemplate;
	}

	public void setBodyTemplate(String bodyTemplate) {
		this.bodyTemplate = bodyTemplate;
	}

	/// Returns the [Selector] used to extract routing information from
	/// the JSON response. The selector's `attribute` field should be a
	/// JsonPath expression (e.g. `$.route.destination`).
	public Selector getResponseSelector() {
		return responseSelector;
	}

	public void setResponseSelector(Selector responseSelector) {
		this.responseSelector = responseSelector;
	}

	/// Resolve with no session attributes. Delegates to
	/// [#resolve(String, Map)] with a null attributes map.
	@Override
	public Translation<T> resolve(String key) throws Exception {
		return resolve(key, null);
	}

	/// Resolve the routing key by calling the configured REST API.
	///
	/// The resolution steps:
	///
	/// 1. Build a substitution map from session attributes + the key
	/// 2. Resolve `${var}` placeholders in the URL template
	/// 3. If POST with `bodyTemplate`, load and resolve the template
	///    file (headers above blank line, JSON body below)
	/// 4. Apply authentication (basic or bearer)
	/// 5. Execute the HTTP request
	/// 6. Parse the JSON response
	/// 7. If `responseSelector` is set, extract the routing URI via
	///    JsonPath; otherwise deserialize the response as the treatment
	/// 8. Return a [Translation] wrapping the treatment, or `null`
	///    if the API returned no match (non-2xx or empty response)
	@SuppressWarnings("unchecked")
	@Override
	public Translation<T> resolve(String key, Map<String, String> attributes) throws Exception {
		if (url == null || key == null) {
			return null;
		}

		// Build substitution map: session attributes + key
		Map<String, String> vars = new HashMap<>();
		if (attributes != null) {
			vars.putAll(attributes);
		}
		vars.put("key", key);

		// Lazy-init HttpClient (thread-safe, reused across calls)
		if (httpClient == null) {
			httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 5))
					.build();
		}

		// Resolve URL placeholders
		String resolvedUrl = resolveVars(url, vars);

		// Build request
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(resolvedUrl))
				.timeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 5));

		// Load and resolve template (POST with bodyTemplate)
		Map<String, String> templateHeaders = null;
		String resolvedBody = null;

		if ("POST".equalsIgnoreCase(method) && bodyTemplate != null) {
			TemplateResult template = loadAndResolveTemplate(bodyTemplate, vars);
			templateHeaders = template.headers;
			resolvedBody = template.body;
		}

		// Set method and body
		if ("POST".equalsIgnoreCase(method)) {
			if (resolvedBody != null) {
				requestBuilder.POST(HttpRequest.BodyPublishers.ofString(resolvedBody));
			} else {
				requestBuilder.POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"" + key + "\"}"));
				requestBuilder.header("Content-Type", "application/json");
			}
		} else {
			requestBuilder.GET();
		}

		// Apply config-level auth (template headers may override)
		if (bearerToken != null) {
			requestBuilder.header("Authorization", "Bearer " + resolveVars(bearerToken, vars));
		} else if (basicAuth != null) {
			String resolved = resolveVars(basicAuth, vars);
			String encoded = Base64.getEncoder().encodeToString(resolved.getBytes());
			requestBuilder.header("Authorization", "Basic " + encoded);
		}

		// Apply template headers (from the template file, after auth
		// so template headers can override config-level Authorization)
		if (templateHeaders != null) {
			for (Map.Entry<String, String> entry : templateHeaders.entrySet()) {
				requestBuilder.header(entry.getKey(), entry.getValue());
			}
		}

		HttpRequest httpRequest = requestBuilder.build();

		if (logger.isLoggable(Level.FINE)) {
			logger.fine("RestResolver[" + id + "]: " + (method != null ? method : "GET") + " " + resolvedUrl);
		}

		// Execute the HTTP request
		HttpResponse<String> httpResponse = httpClient.send(httpRequest,
				HttpResponse.BodyHandlers.ofString());

		if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
			if (logger.isLoggable(Level.FINE)) {
				logger.fine("RestResolver[" + id + "]: HTTP " + httpResponse.statusCode());
			}
			return null;
		}

		String body = httpResponse.body();
		if (body == null || body.isEmpty()) {
			return null;
		}

		if (logger.isLoggable(Level.FINEST)) {
			logger.finest("RestResolver[" + id + "]: response: " + body);
		}

		// Parse JSON response
		JsonNode jsonResponse = mapper.readTree(body);

		// Extract routing decision from response
		if (responseSelector != null) {
			AttributesKey attrsKey = responseSelector.findKey(jsonResponse);
			if (attrsKey != null && attrsKey.key != null) {
				Translation<T> translation = new Translation<>(key);
				translation.setDescription("Resolved via REST: " + id);

				Map<String, Object> treatmentMap = new LinkedHashMap<>();
				treatmentMap.put("requestUri", attrsKey.key);
				if (!attrsKey.attributes.isEmpty()) {
					treatmentMap.put("headers", attrsKey.attributes);
				}

				T treatment = (T) mapper.convertValue(treatmentMap, Object.class);
				translation.setTreatment(treatment);
				return translation;
			}
		} else {
			// No responseSelector — deserialize the entire response
			try {
				Translation<T> translation = new Translation<>(key);
				translation.setDescription("Resolved via REST: " + id);
				T treatment = (T) mapper.treeToValue(jsonResponse, Object.class);
				translation.setTreatment(treatment);
				return translation;
			} catch (Exception e) {
				logger.warning("RestResolver[" + id + "]: failed to deserialize response: " + e.getMessage());
			}
		}

		return null;
	}

	// ------------------------------------------------------------------
	// Template loading and variable resolution
	// ------------------------------------------------------------------

	/// Holds the parsed template: HTTP headers above the blank line
	/// separator, and the JSON body below it.
	private static class TemplateResult {
		Map<String, String> headers = new LinkedHashMap<>();
		String body = "";
	}

	/// Load a template file from the `_templates/` directory, resolve
	/// all `${var}` placeholders, and split into headers + body.
	///
	/// The template format follows HTTP message conventions:
	///
	/// ```
	/// Header-Name: header value with ${var}
	/// Another-Header: another value
	///
	/// {
	///   "json": "body with ${var} placeholders"
	/// }
	/// ```
	///
	/// Everything above the first blank line is parsed as `key: value`
	/// headers. Everything below is the request body. Both sections
	/// have `${var}` placeholders resolved from the provided map.
	///
	/// The raw template is cached after first load for performance.
	/// Variable resolution happens on every call (since variables
	/// change per request).
	///
	/// @param filename the template filename (relative to `_templates/`)
	/// @param vars     the variable substitution map
	/// @return parsed headers and resolved body
	/// @throws IOException if the template file cannot be read
	private TemplateResult loadAndResolveTemplate(String filename, Map<String, String> vars) throws IOException {
		// Load template (cache the raw content on first use)
		if (cachedTemplate == null) {
			Path templatePath = Paths.get(TEMPLATES_DIR, filename);
			if (!Files.exists(templatePath)) {
				throw new IOException("Template not found: " + templatePath);
			}
			cachedTemplate = Files.readString(templatePath);
		}

		// Resolve variables in the entire template
		String resolved = resolveVars(cachedTemplate, vars);

		// Split on first blank line: headers above, body below
		TemplateResult result = new TemplateResult();
		int blankLine = findBlankLine(resolved);

		if (blankLine >= 0) {
			String headerSection = resolved.substring(0, blankLine).trim();
			result.body = resolved.substring(blankLine).trim();

			// Parse headers (key: value format, one per line)
			for (String line : headerSection.split("\\r?\\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				int colon = line.indexOf(':');
				if (colon > 0) {
					String name = line.substring(0, colon).trim();
					String value = line.substring(colon + 1).trim();
					result.headers.put(name, value);
				}
			}
		} else {
			// No blank line found — entire content is the body
			result.body = resolved.trim();
		}

		return result;
	}

	/// Find the index of the first blank line in the text.
	/// Handles both Unix (`\n\n`) and Windows (`\r\n\r\n`) line endings.
	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0) return idx2;
		if (idx2 < 0) return idx;
		return Math.min(idx, idx2);
	}

	/// Simple `${var}` placeholder substitution. Iterates the variable
	/// map and replaces each `${name}` with its value. Unresolved
	/// placeholders are left as-is (no error).
	private static String resolveVars(String template, Map<String, String> vars) {
		if (template == null || vars == null || vars.isEmpty()) {
			return template;
		}
		String result = template;
		for (Map.Entry<String, String> entry : vars.entrySet()) {
			result = result.replace("${" + entry.getKey() + "}", entry.getValue());
		}
		return result;
	}
}
