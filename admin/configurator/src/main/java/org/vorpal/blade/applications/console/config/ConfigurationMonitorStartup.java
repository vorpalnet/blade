package org.vorpal.blade.applications.console.config;

import java.nio.file.Paths;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

// Auto-reload disabled: configuration changes are now applied manually via the
// Reload button in the configurator UI, which calls SettingsMXBean.reload() via JMX.
// @WebListener
public class ConfigurationMonitorStartup implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent sce) {
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
	}

}
