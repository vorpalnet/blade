package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;

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
		out.put("liveRefreshSeconds", s.getLiveRefreshSeconds());
		out.put("analyticsRefreshSeconds", s.getAnalyticsRefreshSeconds());
		// What this viewer may see, so the board can say so instead of showing
		// empty panels. The server enforces it either way.
		Access access = Access.forRequest(req, s);
		out.put("user", req.getRemoteUser());
		out.put("platform", access == null || access.seesAnyPlatform());
		out.put("content", access == null || access.seesContent());
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		MAPPER.writeValue(resp.getWriter(), out);
	}
}
