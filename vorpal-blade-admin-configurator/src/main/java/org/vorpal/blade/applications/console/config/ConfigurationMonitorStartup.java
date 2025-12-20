package org.vorpal.blade.applications.console.config;

import java.nio.file.Paths;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class ConfigurationMonitorStartup implements ServletContextListener {
	private ConfigurationMonitor configurationMonitor;

	@Override
	public void contextInitialized(ServletContextEvent sce) {

		try {
			configurationMonitor = new ConfigurationMonitor();
			configurationMonitor.initialize(Paths.get("./config/custom/vorpal"), true);
			configurationMonitor.start();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {

		try {
			configurationMonitor.interrupt();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
