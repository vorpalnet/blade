package org.vorpal.blade.framework.logging;

import java.text.ParseException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;

public class LogParameters {
	public enum LoggingLevel {
		OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL
	};

	private final static long KB_FACTOR = 1000;
	private final static long KIB_FACTOR = 1024;
	private final static long MB_FACTOR = 1000 * KB_FACTOR;
	private final static long MIB_FACTOR = 1024 * KIB_FACTOR;
	private final static long GB_FACTOR = 1000 * MB_FACTOR;
	private final static long GIB_FACTOR = 1024 * MIB_FACTOR;

	protected Boolean useParentHandlers = null;
	protected String name = null;
	protected String directory = null;
	protected Integer limit = null;
	protected Integer count = null;
	protected Boolean append = null;
	protected LoggingLevel level = null;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setUseParentHandlers(Boolean useParentHandlers) {
		this.useParentHandlers = useParentHandlers;
	}

	public Boolean getUseParentHandlers() {
		return useParentHandlers;
	}

	public void setUseParent(Boolean useParentHandlers) {
		this.useParentHandlers = useParentHandlers;
	}

	public String getDirectory() {
		return directory;
	}

	public void setDirectory(String directory) {
		this.directory = directory;
	}

	public Integer getLimit() {
		return limit;
	}

	public void setLimit(Integer limit) {
		this.limit = limit;
	}

	public Integer getCount() {
		return count;
	}

	public void setCount(Integer count) {
		this.count = count;
	}

	public Boolean getAppend() {
		return append;
	}

	public void setAppend(Boolean append) {
		this.append = append;
	}

	public void setLevel(LoggingLevel level) {
		this.level = level;
	}

	public LoggingLevel getLevel() {
		return level;
	}

	public void LoggingLevel(LoggingLevel level) {
		this.level = level;
	}

	private static int parseHRNumberAsInt(String arg0) throws ParseException {
		float value, ret;
		String factor;

		try {

			Pattern pattern = Pattern.compile("([0-9\\.]+)(([KMG])i?B)*");
			Matcher match = pattern.matcher(arg0);

			if (!match.matches() || match.groupCount() != 3) {
				throw new ParseException("Wrong format", 0);
			} else {

				ret = Float.parseFloat(match.group(1));
				factor = match.group(2);
				if (factor == null) {
					value = ret;
				} else {
					switch (factor) {
					case "GB":
						value = ret * GB_FACTOR;
						break;
					case "GiB":
						value = ret * GIB_FACTOR;
						break;
					case "MB":
						value = ret * MB_FACTOR;
						break;
					case "MiB":
						value = ret * MIB_FACTOR;
						break;
					case "KB":
						value = ret * KB_FACTOR;
						break;
					case "KiB":
						value = ret * KIB_FACTOR;
						break;
					default:
						value = ret;
						break;

					}
				}
			}

			return Math.round(value);
		} catch (Exception e) {
			e.printStackTrace();
			throw new ParseException(
					"Wrong number format in configuration. Example formats include: 1, 2KiB, 3KB, 4MiB, 5MB, 6GiB, or 7.5GB",
					0);
		}

	}

	private static String getAttribute(ServletContext servletContext, String key) {
		String value;

		value = System.getenv().get(key);
		value = (value != null) ? value : System.getProperties().getProperty(key);
		value = (value != null) ? value : servletContext.getInitParameter(key);
		value = (value != null) ? value : (String) servletContext.getAttribute(key);

		return value;
	}

	private static String resolveVariables(ServletContext servletContext, String inputString) {
		int index;
		String variable;
		String key;
		String value;
		String outputString = new String(inputString);
		while ((index = outputString.indexOf("${")) >= 0) {
			variable = outputString.substring(index, outputString.indexOf("}") + 1);
			key = variable.substring(2, variable.length() - 1);
			value = getAttribute(servletContext, key);
			value = (value != null) ? value : "null";
			outputString = outputString.replace(variable, value);
		}

		return outputString;
	}

	public String resolveDirectory(ServletContext servletContext) {
		final String defaultDirectory = "./servers/${weblogic.Name}/logs/vorpal";
		return (directory != null) ? resolveVariables(servletContext, directory)
				: resolveVariables(servletContext, defaultDirectory);
	}

	public String resolveName(ServletContext servletContext) {
		final String defaultName = "${sip.application.name}";
		return (name != null) ? resolveVariables(servletContext, name) : resolveVariables(servletContext, defaultName);
	}

	public Boolean resolveUseParentHandlers() {
		return (useParentHandlers != null) ? useParentHandlers : false;
	}

	public Integer resolveLimit() {
		return (limit != null) ? limit : 50 * 1024 * 1024;
	}

	public Integer resolveCount() {
		return (count != null) ? count : 20;
	}

	public Boolean resolveAppend() {
		return (append != null) ? append : true;
	}

	public Level resolveLevel() {
		if (level == null) {
			return null;
		}

		switch (level) {
		case OFF:
			return Level.OFF;
		case SEVERE:
			return Level.SEVERE;
		case WARNING:
			return Level.WARNING;
		case INFO:
			return Level.INFO;
		case CONFIG:
			return Level.CONFIG;
		case FINE:
			return Level.FINE;
		case FINER:
			return Level.FINER;
		case FINEST:
			return Level.FINEST;
		case ALL:
			return Level.ALL;
		default:
			return Level.INFO;
		}

	}

	public static void main(String args[]) throws ParseException {
		System.out.println("1 = " + LogParameters.parseHRNumberAsInt("1"));
		System.out.println("1KiB = " + LogParameters.parseHRNumberAsInt("1KiB"));
		System.out.println("1KB = " + LogParameters.parseHRNumberAsInt("1KB"));
		System.out.println("1MiB = " + LogParameters.parseHRNumberAsInt("1MiB"));
		System.out.println("1MB = " + LogParameters.parseHRNumberAsInt("1MB"));
		System.out.println("1GiB = " + LogParameters.parseHRNumberAsInt("1GiB"));
		System.out.println("1GB = " + LogParameters.parseHRNumberAsInt("1GB"));
		System.out.println("1.5GB = " + LogParameters.parseHRNumberAsInt("1.5GB"));

	}

}
