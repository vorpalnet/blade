package org.vorpal.blade.applications.console.mxgraph;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Flow app's SettingsManager at startup so its `name` / `tagline` / `description` metadata
/// appears in JMX for the Admin Portal deck and the Configurator form
/// editor. See memory `[[admin-apps-use-settingsmanager]]`.
@WebListener
public class FlowSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(FlowSettingsStartup.class.getName());

	private SettingsManager<FlowSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, FlowSettings.class, new FlowSettingsSample());
			logger.info("flow SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "flow SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "flow SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
