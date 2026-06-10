package org.vorpal.blade.framework.v2.cors;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/// Cross-origin filter for BLADE REST endpoints, registered fleet-wide via the
/// framework's `META-INF/web-fragment.xml` (so it applies to every WAR that
/// bundles the framework JAR — i.e. all of them).
///
/// **It is a complete no-op until configured.** The set of origins allowed to
/// make credentialed cross-origin requests is read from the system property
/// [#ALLOWED_ORIGINS_PROPERTY] (comma-separated, exact match), e.g.
///
/// ```
/// -Dblade.cors.allowedOrigins=https://admin.example.com:7002
/// ```
///
/// With the property unset or empty, the filter passes every request straight
/// through and adds no headers — current behavior is unchanged.
///
/// **Why this exists:** the [API Explorer](https://github.com/scalar/scalar)
/// admin tool (`blade/api`) lets operators fire live "try it" requests from
/// their browser straight at a service's REST endpoints. Those endpoints live
/// on the engine tier, a different origin from the AdminServer the explorer is
/// served from, so the browser needs CORS to allow the call. Reading the
/// service's OpenAPI *document* does not need this — that goes through the
/// explorer's same-origin spec proxy.
///
/// **Security:** only exact origins from the allowlist are honored, and only
/// then is `Access-Control-Allow-Credentials: true` emitted. A wildcard `*` is
/// deliberately not supported, because the wildcard is invalid with credentials
/// and reflecting arbitrary origins with credentials would be unsafe.
///
/// **Exposed response headers:** by default the browser only sees the
/// CORS-safelisted response headers. A service whose browser clients need to
/// read non-safelisted headers (e.g. `Location` after a create, or a
/// service-specific header) can list them via [#EXPOSE_HEADERS_PROPERTY]:
///
/// ```
/// -Dblade.cors.exposeHeaders=Location,X-SEMAFONE-TARGET,Date
/// ```
///
/// Unset ⇒ no `Access-Control-Expose-Headers` is emitted.
public class CorsFilter implements Filter {

	/// System property holding the comma-separated list of exact origins
	/// allowed to make credentialed cross-origin requests. Empty/unset ⇒ no-op.
	public static final String ALLOWED_ORIGINS_PROPERTY = "blade.cors.allowedOrigins";

	/// System property holding the comma-separated list of response headers to
	/// surface to the browser via `Access-Control-Expose-Headers`. Empty/unset ⇒
	/// header is not emitted.
	public static final String EXPOSE_HEADERS_PROPERTY = "blade.cors.exposeHeaders";

	private Set<String> allowedOrigins = Collections.emptySet();
	private String exposeHeaders = null;

	@Override
	public void init(FilterConfig filterConfig) {
		allowedOrigins = parseOrigins(System.getProperty(ALLOWED_ORIGINS_PROPERTY));
		Set<String> expose = parseOrigins(System.getProperty(EXPOSE_HEADERS_PROPERTY));
		exposeHeaders = expose.isEmpty() ? null : String.join(", ", expose);
	}

	/// Parse a comma-separated origin list into a set, trimming blanks. Package
	/// visibility so it can be unit-tested without a servlet container.
	static Set<String> parseOrigins(String csv) {
		Set<String> out = new LinkedHashSet<>();
		if (csv != null) {
			for (String token : csv.split(",")) {
				String trimmed = token.trim();
				if (!trimmed.isEmpty()) {
					out.add(trimmed);
				}
			}
		}
		return out;
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {

		if (allowedOrigins.isEmpty() || !(req instanceof HttpServletRequest)) {
			chain.doFilter(req, resp);
			return;
		}

		HttpServletRequest request = (HttpServletRequest) req;
		HttpServletResponse response = (HttpServletResponse) resp;
		String origin = request.getHeader("Origin");

		if (origin == null || !allowedOrigins.contains(origin)) {
			// Same-origin request, or an origin we don't recognize — leave it
			// untouched so behavior matches the pre-CORS world.
			chain.doFilter(req, resp);
			return;
		}

		response.setHeader("Access-Control-Allow-Origin", origin);
		response.setHeader("Access-Control-Allow-Credentials", "true");
		response.addHeader("Vary", "Origin");
		if (exposeHeaders != null) {
			response.setHeader("Access-Control-Expose-Headers", exposeHeaders);
		}

		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			// Preflight: echo the requested method/headers and cache the grant.
			String requestedMethod = request.getHeader("Access-Control-Request-Method");
			response.setHeader("Access-Control-Allow-Methods",
					requestedMethod != null ? requestedMethod : "GET, POST, PUT, DELETE, OPTIONS");
			String requestedHeaders = request.getHeader("Access-Control-Request-Headers");
			if (requestedHeaders != null) {
				response.setHeader("Access-Control-Allow-Headers", requestedHeaders);
			}
			response.setHeader("Access-Control-Max-Age", "600");
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		}

		chain.doFilter(req, resp);
	}

	@Override
	public void destroy() {
	}
}
