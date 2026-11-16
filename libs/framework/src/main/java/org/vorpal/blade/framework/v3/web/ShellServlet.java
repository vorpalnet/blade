package org.vorpal.blade.framework.v3.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.access.Access;
import org.vorpal.blade.framework.v3.configuration.AppCards;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The frame every admin page shares (the portal's `brand/shell.js`) asks
/// each application, under the application's own sign-in:
///
/// - `blade-shell/me`: who is signed in, how, where "Sign out" goes, and
///   About ([Shell]).
/// - `blade-shell/cards`: the domain's applications and services for the
///   rail ([AppCards]), to the WebLogic admin roles on the AdminServer, the
///   only server that can read the domain. Anyone else gets an empty list,
///   as the dashboard gives a viewer whose grants show no servers.
///
/// Declared in the framework's `web-fragment.xml`, so every application
/// answers without code of its own.
public class ShellServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(ShellServlet.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		String path = (req.getPathInfo() == null) ? "" : req.getPathInfo();
		switch (path) {
		case "/me":
			MAPPER.writeValue(resp.getWriter(), me(req));
			break;
		case "/cards":
			resp.getWriter().write(cards(req));
			break;
		default:
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}

	private Map<String, Object> me(HttpServletRequest req) {
		MBeanServer domain = domainRuntime();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("user", req.getRemoteUser());
		out.put("displayName", Shell.displayName(req.getUserPrincipal(), req.getRemoteUser()));
		out.put("signedInWith", Shell.signedInWith(req.getAuthType()));
		out.put("signOut", Shell.signOut(req.getAuthType(), req.getContextPath(), domain != null));
		Map<String, Object> about = Shell.build(manifest());
		String server = SettingsManager.getServerName();
		about.put("server", server);
		about.put("domain", SettingsManager.getDomainName());
		about.put("weblogic", weblogicVersion(domain, server));
		about.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
		out.put("about", about);
		return out;
	}

	private String cards(HttpServletRequest req) {
		MBeanServer domain = domainRuntime();
		if (domain != null && Access.ADMIN_ROLES.stream().anyMatch(req::isUserInRole)) {
			try {
				return AppCards.json(domain);
			} catch (Exception e) {
				logger.log(Level.WARNING, "shell: application list failed", e);
			}
		}
		return "{\"cards\":[]}";
	}

	private java.util.jar.Attributes manifest() {
		try (InputStream in = getServletContext().getResourceAsStream("/META-INF/MANIFEST.MF")) {
			return (in == null) ? null : new java.util.jar.Manifest(in).getMainAttributes();
		} catch (IOException e) {
			return null;
		}
	}

	/// The domain's runtime MBean server, which only the AdminServer has.
	private static MBeanServer domainRuntime() {
		InitialContext ctx = null;
		try {
			ctx = new InitialContext();
			return (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);
		} catch (NamingException | RuntimeException e) {
			return null;
		} finally {
			if (ctx != null) {
				try {
					ctx.close();
				} catch (NamingException e) {
					// nothing held
				}
			}
		}
	}

	private static String weblogicVersion(MBeanServer domain, String server) {
		if (domain == null || server == null) {
			return null;
		}
		try {
			for (ObjectName on : domain.queryNames(new ObjectName("com.bea:Type=ServerRuntime,Name=" + server + ",*"),
					null)) {
				Object v = domain.getAttribute(on, "WeblogicVersion");
				if (v != null) {
					return v.toString();
				}
			}
		} catch (Exception e) {
			// Unknown: left out.
		}
		return null;
	}
}
