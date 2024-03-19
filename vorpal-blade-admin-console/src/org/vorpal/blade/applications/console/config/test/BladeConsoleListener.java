package org.vorpal.blade.applications.console.config.test;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class BladeConsoleListener implements ServletContextListener {
	private ObjectInstance oi;
	private MBeanServer server;
	private BladeConsole bladeConsole = new BladeConsole();
	private ObjectName objectName;

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		System.out.println(sce.getServletContext().getServletContextName() + " context destroyed...");
		try {
			server.unregisterMBean(objectName);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		System.out.println(sce.getServletContext().getServletContextName() + " context initializing...");

		try {

			System.out.println("Creating objectName...");

			objectName = new ObjectName("vorpal.blade:Name=blade,Type=Configuration");

			System.out.println("Getting server...");
			server = ManagementFactory.getPlatformMBeanServer();

			System.out.println("registerMBean...");

			oi = server.registerMBean(bladeConsole, objectName);

			System.out.println("All good!");

		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println(sce.getServletContext().getServletContextName() + " context initializing... done.");

	}

}
