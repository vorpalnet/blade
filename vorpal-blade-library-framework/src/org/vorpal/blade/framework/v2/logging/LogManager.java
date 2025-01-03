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

package org.vorpal.blade.framework.v2.logging;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import weblogic.kernel.KernelLogManager;

@WebListener
public class LogManager implements ServletContextListener {
	private static String basename;
//	private LogParameters logParameters;

	@Override
	final public void contextInitialized(ServletContextEvent sce) {
		basename = SettingsManager.basename(sce.getServletContext().getServletContextName());
	}

	@Override
	final public void contextDestroyed(ServletContextEvent sce) {
		closeLogger(basename);
	}

	private static ConcurrentHashMap<String, Logger> logMap = new ConcurrentHashMap<String, Logger>();

	public static Logger getLogger(String basename, ServletContext context, LogParameters logParameters) {
		Logger logger;

		// If absolutely nothing is known, give up and use the parent logger
		if (basename == null && context == null && logParameters == null) {
			logger = new Logger(null, null);
			logger.setParent(KernelLogManager.getLogger());
			return logger;
		}

		// Without basename (typical), use servlet context name
		if (basename == null && context != null) {
			basename = SettingsManager.basename(context.getServletContextName());
		}

		// If the logger already exists, use it
		logger = logMap.get(basename);
		if (logger != null) {
			return logger;
		}

		// If no logParameters, use default values
		if (logParameters == null) {
			logParameters = new LogParametersDefault();
		}

		// Okay, we've made it this far, time to build the custom logger
		try {

			String filename;
			if (basename == null) {
				filename = logParameters.resolveFilename(context);
			} else {
				filename = basename + ".%g.log";
			}

			String directory = logParameters.resolveDirectory(context);
			int fileCount = logParameters.resolveFileCount();
			int fileSize = logParameters.resolveFileSize();
			boolean fileAppend = logParameters.resolveFileAppend();
			boolean useParentLogging = logParameters.resolveUseParentLogging();
			Level loggingLevel = logParameters.resolveLoggingLevel();

			File file = new File(directory);
			file.mkdirs();
			String filepath = directory + "/" + filename;
			Formatter formatter = new LogFormatter();
			Handler handler = new FileHandler(filepath, fileSize, fileCount, fileAppend);
			handler.setFormatter(formatter);

			logger = new Logger(basename, null);

			logger.addHandler(handler);
			logger.setParent(KernelLogManager.getLogger());
			logger.setUseParentHandlers(useParentLogging);
			logger.setLevel(loggingLevel); // may be null, but that okay. will use parent's level

			logMap.put(basename, logger);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return logger;
	}

	public static Logger getLogger(String basename) {
		return getLogger(basename, null, null);

	}

	public static Logger getLogger(SipServletContextEvent event) {
		return getLogger(null, event.getServletContext(), null);
	}

	public static Logger getLogger(ServletContext context) {
		return getLogger(null, context, null);
	}

	public static Logger getLogger() {
		return getLogger(null, null, null);
	}

	public static void closeLogger(ServletContextEvent event) {
		closeLogger(SettingsManager.basename(event.getServletContext().getServletContextName()));
	}

	public static void closeLogger(SipServletContextEvent event) {
		closeLogger(SettingsManager.basename(event.getServletContext().getServletContextName()));
	}

	public static void closeLogger(String basename) {

		Logger logger = logMap.remove(basename);
		if (logger != null) {
			for (Handler handler : logger.getHandlers()) {
				handler.close();
			}
		}
	}

}
