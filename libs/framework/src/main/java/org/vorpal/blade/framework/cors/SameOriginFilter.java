package org.vorpal.blade.framework.cors;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/// Refuses cross-site request forgery: a state-changing request a browser sends
/// on behalf of a page from another site.
///
/// The admin apps authenticate with a session cookie, which the browser attaches
/// to any request to the AdminServer, whichever page caused it. Without a check,
/// a page an operator merely visits could publish an FSMAR config, drain a
/// server, or write a file with the operator's authority. A browser always says
/// which page started a request, in the `Origin` header (or failing that
/// `Referer`), and a page cannot forge it.
///
/// A request is refused with 403 when all of these hold:
///
/// - it can change state: any method but GET, HEAD and OPTIONS, or a WebSocket
///   upgrade (a WebSocket carries the cookie too);
/// - it names a source page, in `Origin` or `Referer`;
/// - that page is from another site: its host and port match neither the
///   request's `Host` nor `X-Forwarded-Host`, and it is not one of the origins
///   in `blade.cors.allowedOrigins`, the list [CorsFilter] already grants
///   cross-origin access to.
///
/// A request with no `Origin` or `Referer` passes: browsers send one on every
/// cross-site request that could change state, so its absence means a script or
/// tool, which has no stolen cookie to ride on. An `Origin` of `null`, which a
/// sandboxed or privacy-stripped page sends, is refused.
///
/// Registered for every WAR in the framework's `web-fragment.xml`. SIP traffic
/// never passes servlet filters.
public class SameOriginFilter implements Filter {

	private static final Logger LOG = Logger.getLogger(SameOriginFilter.class.getName());

	private Set<String> allowedOrigins = Collections.emptySet();

	@Override
	public void init(FilterConfig filterConfig) {
		allowedOrigins = CorsFilter.parseOrigins(System.getProperty(CorsFilter.ALLOWED_ORIGINS_PROPERTY));
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {
		if (req instanceof HttpServletRequest) {
			HttpServletRequest request = (HttpServletRequest) req;
			if (!allowed(request.getMethod(), request.getHeader("Upgrade"), request.getHeader("Origin"),
					request.getHeader("Referer"), request.getHeader("Host"), request.getHeader("X-Forwarded-Host"),
					allowedOrigins)) {
				LOG.log(Level.WARNING, "SameOriginFilter refused " + request.getMethod() + " "
						+ request.getRequestURI() + " from origin " + request.getHeader("Origin")
						+ " (referer " + request.getHeader("Referer") + ") to host " + request.getHeader("Host"));
				((HttpServletResponse) resp).sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site request refused");
				return;
			}
		}
		chain.doFilter(req, resp);
	}

	/// The decision, without a container; see the class comment. Public so a
	/// WebSocket endpoint can apply it at its handshake.
	public static boolean allowed(String method, String upgrade, String origin, String referer, String host,
			String forwardedHost, Set<String> allowedOrigins) {
		boolean changesState = !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
				|| "OPTIONS".equalsIgnoreCase(method)) || "websocket".equalsIgnoreCase(upgrade);
		if (!changesState) {
			return true;
		}

		String source = (origin != null) ? origin.trim() : null;
		if ("null".equals(source)) {
			return false;
		}
		if (source == null || source.isEmpty()) {
			source = referer;
		}
		if (source == null || source.trim().isEmpty()) {
			return true;
		}

		URI uri;
		try {
			uri = URI.create(source.trim());
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (uri.getScheme() == null || uri.getHost() == null) {
			return false;
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (allowedOrigins.contains(scheme + "://" + uri.getRawAuthority())) {
			return true;
		}

		String sourceAuthority = authority(scheme, uri.getHost(), uri.getPort());
		return sourceAuthority.equals(hostHeaderAuthority(scheme, host))
				|| sourceAuthority.equals(hostHeaderAuthority(scheme, firstForwarded(forwardedHost)));
	}

	/// Host and port, lower-cased, with the scheme's default port dropped.
	private static String authority(String scheme, String host, int port) {
		String h = host.toLowerCase(Locale.ROOT);
		boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
		return defaultPort ? h : h + ":" + port;
	}

	private static String hostHeaderAuthority(String scheme, String hostHeader) {
		if (hostHeader == null || hostHeader.trim().isEmpty()) {
			return null;
		}
		try {
			URI uri = URI.create(scheme + "://" + hostHeader.trim());
			return (uri.getHost() != null) ? authority(scheme, uri.getHost(), uri.getPort()) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/// A proxy chain lists hosts comma-separated; the first is what the browser used.
	private static String firstForwarded(String forwardedHost) {
		if (forwardedHost == null) {
			return null;
		}
		int comma = forwardedHost.indexOf(',');
		return (comma < 0) ? forwardedHost : forwardedHost.substring(0, comma);
	}
}
