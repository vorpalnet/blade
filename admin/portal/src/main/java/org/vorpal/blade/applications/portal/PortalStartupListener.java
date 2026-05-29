package org.vorpal.blade.applications.portal;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the portal's SettingsManager at WAR startup so the framework
/// auto-creates the `vorpal.blade:Name=portal,Type=Configuration,*` MBean
/// — which is how the Configurator app discovers what's editable.
///
/// Settings live at `./config/custom/vorpal/portal.json` (Domain scope).
/// A working SAMPLE is auto-emitted to `./config/custom/vorpal/samples/`
/// on first boot, derived from {@link PortalSettingsSample}.
@WebListener
public class PortalStartupListener implements ServletContextListener {

	private static final Logger log = Logger.getLogger(PortalStartupListener.class.getName());

	/// Shared static accessor — ManifestResource pulls current settings from here.
	/// Same pattern every BLADE service uses (cf. OptionsSipServlet.settingsManager).
	public static SettingsManager<PortalSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<>(event, PortalSettings.class, new PortalSettingsSample());
			log.info("Portal SettingsManager registered; config at ./config/custom/vorpal/portal.json");
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to register portal SettingsManager — manifest endpoint will fall back to bundled defaults", e);
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
}
