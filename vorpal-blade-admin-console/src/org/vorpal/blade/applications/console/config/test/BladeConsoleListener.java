package org.vorpal.blade.applications.console.config.test;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.hsqldb.Server;

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
			
			System.out.println("Starting HSQLDB at location: file:~/.xwiki/data/xwiki.db");
			Server server = new Server();
			server.setDatabaseName(0, "test");
			server.setDatabasePath(0, "file:~/.xwiki/data/xwiki.db");
			server.start();
			System.out.println("HSQLDB started successfully.");
			
			
			

//			System.out.println("Creating objectName...");
//
//			objectName = new ObjectName("vorpal.blade:Name=blade,Type=Configuration");
//
//			System.out.println("Getting server...");
//			server = ManagementFactory.getPlatformMBeanServer();
//
//			System.out.println("registerMBean...");
//
//			oi = server.registerMBean(bladeConsole, objectName);
//
//			System.out.println("All good!");

		} catch (Exception e) {
			System.out.println("HSQLDB failed to start.");
			e.printStackTrace();
		}

		System.out.println(sce.getServletContext().getServletContextName() + " context initializing... done.");

	}

}
