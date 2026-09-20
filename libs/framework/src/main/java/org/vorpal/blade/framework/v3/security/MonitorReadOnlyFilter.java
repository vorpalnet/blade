package org.vorpal.blade.framework.v3.security;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/// Keeps the Monitor role read-only in every BLADE web app.
///
/// Each admin app's `web.xml` admits all four admin roles to the whole app, and
/// most endpoints do not check the role again, so a Monitor user could publish a
/// config, write a file, restart a server or mint a token. This filter refuses
/// any request that can change state (every method but GET, HEAD and OPTIONS)
/// from a user who holds Monitor and none of Admin, Operator or Deployer.
///
/// Only Monitor is narrowed. An authenticated user with no admin role at all,
/// such as a service account posting events, is left to the app's own
/// constraints, as before. Endpoints that need a stronger role than "not
/// Monitor" still check it themselves.
///
/// Registered for every WAR in the framework's `web-fragment.xml`.
public class MonitorReadOnlyFilter implements Filter {

	private static final Logger LOG = Logger.getLogger(MonitorReadOnlyFilter.class.getName());

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {
		if (req instanceof HttpServletRequest) {
			HttpServletRequest request = (HttpServletRequest) req;
			if (changesState(request.getMethod()) && readOnly(request)) {
				LOG.log(Level.WARNING, "MonitorReadOnlyFilter refused " + request.getMethod() + " "
						+ request.getRequestURI() + " for " + request.getRemoteUser());
				((HttpServletResponse) resp).sendError(HttpServletResponse.SC_FORBIDDEN,
						"The Monitor role is read-only");
				return;
			}
		}
		chain.doFilter(req, resp);
	}

	static boolean changesState(String method) {
		return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
				|| "OPTIONS".equalsIgnoreCase(method));
	}

	/// True for a user holding Monitor and no role that may change anything.
	static boolean readOnly(HttpServletRequest request) {
		return request.isUserInRole(AdminRole.MONITOR.roleName())
				&& !request.isUserInRole(AdminRole.ADMIN.roleName())
				&& !request.isUserInRole(AdminRole.OPERATOR.roleName())
				&& !request.isUserInRole(AdminRole.DEPLOYER.roleName());
	}
}
