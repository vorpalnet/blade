package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.security.JwtIdentity;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The settings the board applies in the browser: when a heap reads high and
/// how often it refreshes. The thresholds the reports apply stay on the
/// server ([AnalyticsServlet]). Read once when the board opens, so a change in
/// the Configurator reaches a board on its next load.
///
/// URL: `/blade/dashboard/settings`.
@WebServlet("/settings")
public class BoardSettingsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	@SuppressWarnings("unchecked")
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		Object sm = getServletContext().getAttribute(DashboardSettingsStartup.SETTINGS_ATTR);
		DashboardSettings s = (sm instanceof SettingsManager) ? ((SettingsManager<DashboardSettings>) sm).getCurrent()
				: null;
		if (s == null) {
			s = new DashboardSettings();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("heapHighPercent", s.getHeapHighPercent());
		out.put("healthyPercent", s.getHealthyPercent());
		out.put("unhealthyPercent", Math.min(s.getUnhealthyPercent(), s.getHealthyPercent() - 1));
		out.put("liveRefreshSeconds", s.getLiveRefreshSeconds());
		out.put("capacityCallsPerSecond", s.getCapacityCallsPerSecond());
		out.put("capacitySessions", s.getCapacitySessions());
		out.put("analyticsRefreshSeconds", s.getAnalyticsRefreshSeconds());
		out.put("ranges", s.getRanges());
		out.put("defaultDays", s.getDefaultDays());
		// What this viewer may see, so the board can say so instead of showing
		// empty panels. The server enforces it either way.
		Access access = Access.forRequest(req, s);
		out.put("user", req.getRemoteUser());
		out.put("displayName", displayName(req));
		out.put("signedInWith", "OIDC".equals(req.getAuthType()) ? "identity domain" : "WebLogic");
		out.put("sees", Access.describe(access));
		out.put("platform", access == null || access.seesAnyPlatform());
		out.put("content", access == null || access.seesContent());
		out.put("about", about());
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		MAPPER.writeValue(resp.getWriter(), out);
	}

	/// What the brand's About panel says: this build, from the WAR's own
	/// manifest (version, build date and mode, copyright, license), and what it
	/// runs on. Read on each request; it is a few attribute reads.
	private Map<String, Object> about() {
		Map<String, Object> out = new LinkedHashMap<>();
		try (java.io.InputStream in = getServletContext().getResourceAsStream("/META-INF/MANIFEST.MF")) {
			if (in != null) {
				java.util.jar.Attributes a = new java.util.jar.Manifest(in).getMainAttributes();
				out.put("version", a.getValue("Implementation-Version"));
				out.put("built", a.getValue("Implementation-Build-Date"));
				out.put("mode", a.getValue("Blade-Build-Mode"));
				out.put("copyright", a.getValue("Copyright"));
				out.put("license", a.getValue("License"));
			}
		} catch (Exception e) {
			// The panel shows what it has.
		}
		String server = SettingsManager.getServerName();
		out.put("server", server);
		out.put("domain", SettingsManager.getDomainName());
		out.put("weblogic", weblogicVersion(server));
		out.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
		return out;
	}

	/// The `WeblogicVersion` of the server this runs on, or null.
	private static String weblogicVersion(String server) {
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			javax.management.MBeanServer dr = (javax.management.MBeanServer) ctx
					.lookup("java:comp/env/jmx/domainRuntime");
			for (javax.management.ObjectName on : dr.queryNames(
					new javax.management.ObjectName("com.bea:Type=ServerRuntime,Name=" + server + ",*"), null)) {
				Object v = dr.getAttribute(on, "WeblogicVersion");
				if (v != null) {
					return v.toString();
				}
			}
		} catch (Exception e) {
			// Unknown: left out.
		}
		return null;
	}

	/// The person's name as the identity domain gives it, else the user name.
	private static String displayName(HttpServletRequest req) {
		if (req.getUserPrincipal() instanceof JwtIdentity) {
			JwtIdentity id = (JwtIdentity) req.getUserPrincipal();
			for (String claim : new String[] { "user_displayname", "name", "preferred_username" }) {
				String v = id.claim(claim);
				if (v != null && !v.isBlank()) {
					return v;
				}
			}
		}
		return req.getRemoteUser();
	}
}
