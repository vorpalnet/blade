package org.vorpal.blade.applications.testconsole;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Test Console's SettingsManager at startup so the app's
/// `name` / `tagline` / `description` appear on the Admin Portal launcher
/// deck (read over JMX from `SettingsMXBean.getCurrentJson()`).
@WebListener
public class TestConsoleStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(TestConsoleStartup.class.getName());

	private SettingsManager<TestConsoleSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, TestConsoleSettings.class, new TestConsoleSettingsSample());
			logger.info("test-console SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "test-console SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "test-console SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
