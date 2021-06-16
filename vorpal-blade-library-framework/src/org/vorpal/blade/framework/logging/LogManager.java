package org.vorpal.blade.framework.logging;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import weblogic.kernel.KernelLogManager;

@WebListener
public class LogManager implements ServletContextListener {
	private static ConcurrentHashMap<String, Logger> logMap = new ConcurrentHashMap<String, Logger>();

	public static Logger getLogger(ServletContext context) {

		Logger logger = null;

		String name = context.getServletContextName();

		try {
			logger = logMap.get(name);

			if (logger == null) {
				String directory = "./servers/" + System.getProperty("weblogic.Name") + "/logs/vorpal";
				File file = new File(directory);
				file.mkdirs();
				String filepath = directory + "/" + name + ".%g.log";

				Formatter formatter = new LogFormatter();
				Handler handler = new FileHandler(filepath, 10 * 1024 * 1024, 10, true);

				handler.setFormatter(formatter);

				logger = new Logger(context.getServletContextName(), null);

				logger.addHandler(handler);
				logger.setParent(KernelLogManager.getLogger());
				logger.setUseParentHandlers(false);

				logMap.put(name, logger);

			}
		} catch (Exception ex) {
			System.out.println("Unable to create new logger for ServletContext: " + name);
			ex.printStackTrace();
		}

		return logger;
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		Logger logger;
		String name = event.getServletContext().getServletContextName();

		logger = logMap.remove(name);
		if (logger != null) {
			logger.severe("LogManager destroying logger for " + name);
			for (Handler handler : logger.getHandlers()) {
				handler.close();
			}
		}
	}

	@Override
	public void contextInitialized(ServletContextEvent event) {
		// Initialize logger
		Logger logger = getLogger(event.getServletContext());
		logger.severe("LogManager creating new logger for " + event.getServletContext().getServletContextName());
	}

}
