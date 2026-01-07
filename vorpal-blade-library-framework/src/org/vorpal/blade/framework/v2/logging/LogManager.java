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

/**
 * Manages application-specific loggers with file-based output.
 * Automatically creates and configures loggers on context initialization.
 */
@WebListener
public class LogManager implements ServletContextListener {

	private static final String LOG_FILE_SUFFIX = ".%g.log";
	private static final String DEFAULT_LOGGER_NAME = "BLADE";
	private static final String PATH_SEPARATOR = "/";

	private static String basename;

	@Override
	public final void contextInitialized(ServletContextEvent sce) {
		if (sce != null && sce.getServletContext() != null) {
			basename = SettingsManager.basename(sce.getServletContext().getServletContextName());
		}
	}

	@Override
	public final void contextDestroyed(ServletContextEvent sce) {
		closeLogger(basename);
	}

	private static final ConcurrentHashMap<String, Logger> logMap = new ConcurrentHashMap<>();

	public static Logger getLogger(String basename, ServletContext context, LogParameters logParameters) {
		Logger logger = null;

		// If absolutely nothing is known, give up and use the parent logger
		if (basename == null && context == null && logParameters == null) {
			logger = new Logger(null, null);

			java.util.logging.Logger parentLogger = KernelLogManager.getLogger();
			if (parentLogger == null) {
				parentLogger = java.util.logging.Logger.getLogger(DEFAULT_LOGGER_NAME);
			}
			logger.setParent(parentLogger);

			return logger;
		}

		// Without basename (typical), use servlet context name
		String effectiveBasename = basename;
		if (effectiveBasename == null && context != null) {
			effectiveBasename = SettingsManager.basename(context.getServletContextName());
		}

		// If the logger already exists, use it
		if (effectiveBasename != null) {
			logger = logMap.get(effectiveBasename);
			if (logger != null) {
				return logger;
			}
		}

		// If no logParameters, use default values
		LogParameters effectiveLogParameters = logParameters;
		if (effectiveLogParameters == null) {
			effectiveLogParameters = new LogParametersDefault();
		}

		// Okay, we've made it this far, time to build the custom logger
		try {

			String filename;
			if (effectiveBasename == null) {
				filename = effectiveLogParameters.resolveFilename(context);
			} else {
				filename = effectiveBasename + LOG_FILE_SUFFIX;
			}

			String directory = effectiveLogParameters.resolveDirectory(context);
			int fileCount = effectiveLogParameters.resolveFileCount();
			int fileSize = effectiveLogParameters.resolveFileSize();
			boolean fileAppend = effectiveLogParameters.resolveFileAppend();
			boolean useParentLogging = effectiveLogParameters.resolveUseParentLogging();
			Level loggingLevel = effectiveLogParameters.resolveLoggingLevel();

			File file = new File(directory);
			file.mkdirs();
			String filepath = directory + PATH_SEPARATOR + filename;
			Formatter formatter = new LogFormatter();
			Handler handler = new FileHandler(filepath, fileSize, fileCount, fileAppend);
			handler.setFormatter(formatter);

			logger = new Logger(effectiveBasename, null);

			logger.addHandler(handler);

			java.util.logging.Logger parentLogger = KernelLogManager.getLogger();
			if (parentLogger == null) {
				parentLogger = java.util.logging.Logger.getLogger(DEFAULT_LOGGER_NAME);
			}
			logger.setParent(parentLogger);

			logger.setUseParentHandlers(useParentLogging);
			logger.setLevel(loggingLevel); // may be null, but that okay. will use parent's level

			if (effectiveBasename != null) {
				logMap.put(effectiveBasename, logger);
			}
		} catch (Exception e) {
			// Log to stderr since the logger is not yet available
			System.err.println("Failed to create logger: " + e.getMessage());
			e.printStackTrace();
			// Return a fallback logger if creation failed
			java.util.logging.Logger parentLogger = KernelLogManager.getLogger();
			if (parentLogger == null) {
				parentLogger = java.util.logging.Logger.getLogger(DEFAULT_LOGGER_NAME);
			}
			logger = new Logger(effectiveBasename != null ? effectiveBasename : DEFAULT_LOGGER_NAME, null);
			logger.setParent(parentLogger);
		}

		return logger;
	}

	public static Logger getLogger(String basename) {
		return getLogger(basename, null, null);

	}

	public static Logger getLogger(SipServletContextEvent event) {
		if (event == null) {
			return getLogger(null, null, null);
		}
		return getLogger(null, event.getServletContext(), null);
	}

	public static Logger getLogger(ServletContext context) {
		return getLogger(null, context, null);
	}

	public static Logger getLogger() {
		return getLogger(null, null, null);
	}

	public static void closeLogger(ServletContextEvent event) {
		if (event != null && event.getServletContext() != null) {
			closeLogger(SettingsManager.basename(event.getServletContext().getServletContextName()));
		}
	}

	public static void closeLogger(SipServletContextEvent event) {
		if (event != null && event.getServletContext() != null) {
			closeLogger(SettingsManager.basename(event.getServletContext().getServletContextName()));
		}
	}

	public static void closeLogger(String basename) {
		if (basename == null) {
			return;
		}
		Logger logger = logMap.remove(basename);
		if (logger != null) {
			for (Handler handler : logger.getHandlers()) {
				if (handler != null) {
					handler.close();
				}
			}
		}
	}

}
