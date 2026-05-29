package org.vorpal.blade.applications.javadoc;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Javadoc admin app's SettingsManager at startup so its
/// `name` / `tagline` / `description` metadata appear in JMX for the Admin
/// Portal launcher card. See memory `[[admin-apps-use-settingsmanager]]`.
@WebListener
public class JavadocSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(JavadocSettingsStartup.class.getName());

	private SettingsManager<JavadocSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, JavadocSettings.class, new JavadocSettingsSample());
			logger.info("javadoc SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "javadoc SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "javadoc SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
