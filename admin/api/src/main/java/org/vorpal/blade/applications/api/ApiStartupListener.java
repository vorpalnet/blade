package org.vorpal.blade.applications.api;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the API Explorer's SettingsManager at WAR startup so the framework
/// auto-creates the `vorpal.blade:Name=api,Type=Configuration,*` MBean — which
/// makes the app visible in the Configurator's app dropdown and on the Portal
/// deck. Settings live at `./config/custom/vorpal/api.json` (Domain scope).
@WebListener
public class ApiStartupListener implements ServletContextListener {

	private static final Logger log = Logger.getLogger(ApiStartupListener.class.getName());

	/// Shared static accessor — the JAX-RS resources read the current engine
	/// base URL from here. Same pattern the portal uses
	/// (`PortalStartupListener.settingsManager`).
	public static SettingsManager<ApiSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<>(event, ApiSettings.class, new ApiSettingsSample());
			log.info("API Explorer SettingsManager registered; config at ./config/custom/vorpal/api.json");
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to register API Explorer SettingsManager — discovery and spec proxy will return 503 until configured", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		try {
			if (settingsManager != null) {
				settingsManager.unregister();
			}
		} catch (Exception e) {
			// best-effort on shutdown
		}
	}

	/// Current engine-tier base URL with any trailing slash removed, or `null`
	/// if settings are unavailable or the field is blank.
	static String engineBaseUrl() {
		SettingsManager<ApiSettings> sm = settingsManager;
		if (sm == null || sm.getCurrent() == null) {
			return null;
		}
		String url = sm.getCurrent().getEngineBaseUrl();
		if (url == null) {
			return null;
		}
		url = url.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url.isEmpty() ? null : url;
	}
}
