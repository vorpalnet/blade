/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

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
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.Configuration;
import org.vorpal.blade.framework.config.SettingsManager;

import weblogic.kernel.KernelLogManager;

@WebListener
public class LogManager2 implements ServletContextListener {
	private static ConcurrentHashMap<String, Logger> logMap = new ConcurrentHashMap<String, Logger>();

	public static Logger getLogger(SipServletContextEvent event) {
		String basename = SettingsManager.basename(event.getServletContext().getServletContextName());
		return getLogger(basename);
	}

	public static Logger getLogger(ServletContext context) {
		String basename = SettingsManager.basename(context.getServletContextName());
		return getLogger(basename);
	}

	public static Logger getLogger(String name) {
		name = SettingsManager.basename(name);
		Logger logger = null;

		try {
			logger = logMap.get(name);

			if (logger == null) {
				String directory = "./servers/" + System.getProperty("weblogic.Name") + "/logs/vorpal";
				File file = new File(directory);
				file.mkdirs();
				String filepath = directory + "/" + name + ".%g.log";

				Formatter formatter = new LogFormatter();
				Handler handler = new FileHandler(filepath, 50 * 1024 * 1024, 20, true);

				handler.setFormatter(formatter);

				logger = new Logger(name, null);

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

	public static Logger getLogger(ServletContext servletContext, LogParameters logging) {
		Logger logger = null;
		String name = null;

		if (logging == null) {
			logging = new LogParametersDefault();
		}

		try {
			name = SettingsManager.basename(logging.resolveName(servletContext));
			logger = logMap.get(name);

			if (logger == null) {

				String directory = logging.resolveDirectory(servletContext);
				File file = new File(directory);
				file.mkdirs();
				String filepath = directory + "/" + name + ".%g.log";

//				Handler handler = new FileHandler(filepath, logging.resolveLimit(), logging.resolveCount(),
//						logging.resolveAppend());
				Handler handler = new FileHandler(filepath, logging.resolveLimit(), logging.resolveCount(), true);
				handler.setFormatter(new LogFormatter());

				logger = new Logger(name, null);
				logger.addHandler(handler);
				logger.setParent(KernelLogManager.getLogger());
//				logger.setUseParentHandlers(logging.resolveUseParentHandlers());
				logger.setUseParentHandlers(false);

//				System.out.println(name+" LogManager logger config level: " + logging.getLevel());
				if (logging.getLevel() != null) {
					logger.setLevel(logging.resolveLevel());
//					System.out.println(name+" LogManager set logging level to: " + logger.getLevel());
				}

				logMap.put(name, logger);
			}
		} catch (Exception ex) {
			System.out.println("Unable to create new logger for ServletContext: " + name);
			ex.printStackTrace();
		}

		return logger;
	}

	/*
	 * public static Logger getLogger(ServletContext servletContext, Configuration
	 * config) { try { LogParameters logParameters = (config != null &&
	 * config.getLogging() != null) ? config.getLogging() : new
	 * LogParametersDefault();
	 * 
	 * String name = SettingsManager.basename(replaceAttributes(servletContext,
	 * config, inputString){
	 * 
	 * }
	 * 
	 * 
	 * // name = SettingsManager.basename(name); // Logger logger =
	 * logMap.get(name);
	 * 
	 * // String directory = "./servers/" + System.getProperty("weblogic.Name") +
	 * "/logs/vorpal";
	 * 
	 * 
	 * File file = new File(directory); file.mkdirs(); String filepath = directory +
	 * "/" + name + ".%g.log";
	 * 
	 * Formatter formatter = new LogFormatter(); Handler handler = new
	 * FileHandler(filepath, 10 * 1024 * 1024, 10, true);
	 * 
	 * handler.setFormatter(formatter);
	 * 
	 * logger = new Logger(name, null);
	 * 
	 * logger.addHandler(handler); logger.setParent(KernelLogManager.getLogger());
	 * logger.setUseParentHandlers(false);
	 * 
	 * logMap.put(name, logger);
	 * 
	 * } catch (Exception ex) {
	 * System.out.println("Unable to create new logger for ServletContext: " +
	 * name); ex.printStackTrace(); }
	 * 
	 * return logger; }
	 */

	public void closeLogger(ServletContextEvent event) {
		if (event != null && event.getServletContext() != null
				&& event.getServletContext().getServletContextName() != null) {
			closeLogger(event.getServletContext().getServletContextName());
		} else {
			closeLogger("vorpal");
		}
	}

	public static void closeLogger(String name) {
		Logger logger = logMap.remove(name);
		if (logger != null) {
//			logger.info("LogManager destroying logger for " + name);
			for (Handler handler : logger.getHandlers()) {
				handler.close();
			}
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		closeLogger(event);
	}

	@Override
	public void contextInitialized(ServletContextEvent event) {
		// SettingsManager is now used to initialize the Logger.

//		Logger logger;
//
//		if (event != null && event.getServletContext() != null
//				&& event.getServletContext().getServletContextName() != null) {
//			logger = getLogger(event.getServletContext());
//			logger.info("LogManager creating new logger for " + event.getServletContext().getServletContextName());
//		} else {
//			logger = getLogger("vorpal");
//			System.out.println("No ServletContext found, creating logger for vorpal.log");
//		}
	}

}
