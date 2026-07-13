package org.vorpal.blade.applications.balancer;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Balancer Health app's SettingsManager at startup so the
/// app's `name` / `tagline` / `description` appear on the Admin Portal
/// launcher deck (read over JMX from `SettingsMXBean.getCurrentJson()`).
@WebListener
public class BalancerStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(BalancerStartup.class.getName());

	private static SettingsManager<BalancerSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, BalancerSettings.class, new BalancerSettingsSample());
			logger.info("balancer SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "balancer SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "balancer SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
