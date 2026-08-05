package org.vorpal.blade.admin.irouter;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the iRouter Editor's SettingsManager at startup so its
/// `name` / `tagline` / `description` metadata (and any future per-app
/// settings) appear in JMX for the Admin Portal deck and the Configurator
/// form editor — same pattern as every admin app.
@WebListener
public class IRouterEditorSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(IRouterEditorSettingsStartup.class.getName());

	private SettingsManager<IRouterEditorSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, IRouterEditorSettings.class,
					new IRouterEditorSettingsSample());
			logger.info("irouter-editor SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "irouter-editor SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "irouter-editor SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
