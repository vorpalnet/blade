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

	public enum Unit {
		KB, KiB, MB, MiB, GB, GiB
	}

	private final static long KB_FACTOR = 1000;
	private final static long KIB_FACTOR = 1024;
	private final static long MB_FACTOR = 1000 * KB_FACTOR;
	private final static long MIB_FACTOR = 1024 * KIB_FACTOR;
	private final static long GB_FACTOR = 1000 * MB_FACTOR;
	private final static long GIB_FACTOR = 1024 * MIB_FACTOR;

	protected Boolean useParentLogging = null;
	protected String directory = null;
	protected String filename = null;
	protected String fileSize = null;
	protected Integer fileCount = null;
	protected Boolean appendFile = null;
	protected LoggingLevel loggingLevel = null;
	protected LoggingLevel sequenceDiagramLoggingLevel = null;
	protected LoggingLevel configurationLoggingLevel = null;

	public LogParameters() {

	}

	public LogParameters(LogParameters that) {
		this.useParentLogging = that.useParentLogging;
		this.directory = that.directory;
		this.filename = that.filename;
		this.fileSize = that.fileSize;
		this.fileCount = that.fileCount;
		this.appendFile = that.appendFile;
		this.loggingLevel = that.loggingLevel;
		this.sequenceDiagramLoggingLevel = that.sequenceDiagramLoggingLevel;
		this.configurationLoggingLevel = that.configurationLoggingLevel;
	}

	public LoggingLevel getLoggingLevel() {
		return loggingLevel;
	}

	/**
	 * If true, statements will appear in the standard WebLogic log files. If false,
	 * statements will appear in custom application specific log files.
	 * 
	 * @return the useParentLogging
	 */
	public Boolean getUseParentLogging() {
		return useParentLogging;
	}

	/**
	 * If true, statements will appear in the standard WebLogic log files. If false,
	 * statements will appear in custom application specific log files.
	 * 
	 * @param useParentLogging the useParentLogging value
	 * @return instance of self
	 */
	public LogParameters setUseParentLogging(Boolean useParentLogging) {
		this.useParentLogging = useParentLogging;
		return this;
	}

	/**
	 * @return the directory
	 */
	public String getDirectory() {
		return directory;
	}

	/**
	 * @param directory the directory to set
	 * @return instance of self
	 */
	public LogParameters setDirectory(String directory) {
		this.directory = directory;
		return this;
	}

	/**
	 * @return the fileName
	 */
	public String getFileName() {
		return filename;
	}

	/**
	 * @param fileName the fileName to set
	 * @return instance of self
	 */
	public LogParameters setFileName(String fileName) {
		this.filename = fileName;
		return this;
	}

	/**
	 * @return the fileSize
	 */
	public String getFileSize() {
		return fileSize;
	}

	/**
	 * @param fileSize the fileSize to set
	 * @return instance of self
	 */
	public LogParameters setFileSize(String fileSize) {
		this.fileSize = fileSize;
		return this;
	}

	/**
	 * @return the fileCount
	 */
	public Integer getFileCount() {
		return fileCount;
	}

	/**
	 * @param fileCount the fileCount to set
	 * @return instance of self
	 */
	public LogParameters setFileCount(Integer fileCount) {
		this.fileCount = fileCount;
		return this;
	}

	/**
	 * @return the appendFile
	 */
	public Boolean getAppendFile() {
		return appendFile;
	}

	/**
	 * @param appendFile the appendFile to set
	 * @return instance of self
	 */
	public LogParameters setAppendFile(Boolean appendFile) {
		this.appendFile = appendFile;
		return this;
	}

	/**
	 * @return the sequenceDiagramLoggingLevel
	 */
	public LoggingLevel getSequenceDiagramLoggingLevel() {
		return sequenceDiagramLoggingLevel;
	}

	/**
	 * @param sequenceDiagramLoggingLevel the sequenceDiagramLoggingLevel to set
	 * @return instance of self
	 */
	public LogParameters setSequenceDiagramLoggingLevel(LoggingLevel sequenceDiagramLoggingLevel) {
		this.sequenceDiagramLoggingLevel = sequenceDiagramLoggingLevel;
		return this;
	}

	public Level resolveSequenceDiagramLoggingLevel() {
		return parseLoggingLevel(this.getSequenceDiagramLoggingLevel());
	}

	public Level parseLoggingLevel(LoggingLevel level) {
		if (level == null) {
			return Level.FINE;
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

	/**
	 * @return the configurationLoggingLevel
	 */
	public LoggingLevel getConfigurationLoggingLevel() {
		return configurationLoggingLevel;
	}

	/**
	 * @param configurationLoggingLevel the configurationLoggingLevel to set
	 * @return instance of self
	 */
	public LogParameters setConfigurationLoggingLevel(LoggingLevel configurationLoggingLevel) {
		this.configurationLoggingLevel = configurationLoggingLevel;
		return this;
	}

	public Level resolveConfigurationLoggingLevel() {
		return parseLoggingLevel(this.getConfigurationLoggingLevel());
	}

	/**
	 * @param loggingLevel the loggingLevel to set
	 * @return instance of self
	 */
	public LogParameters setLoggingLevel(LoggingLevel loggingLevel) {
		this.loggingLevel = loggingLevel;
		return this;
	}

	private static int parseHRNumberAsInt(String humanReadable) throws ParseException {
		float value, ret;
		String factor;

		try {

			Pattern pattern = Pattern.compile("([0-9\\.]+)(([KMG])i?B)*");
			Matcher match = pattern.matcher(humanReadable);

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

	public static String resolveVariables(ServletContext servletContext, String inputString) {
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

	public String resolveFilename(ServletContext servletContext) {
		final String defaultName = "${sip.application.name}.%g.log";
		return (filename != null) ? resolveVariables(servletContext, filename)
				: resolveVariables(servletContext, defaultName);
	}

	public Boolean resolveUseParentLogging() {
		return (useParentLogging != null) ? useParentLogging : false;
	}

	public Integer resolveFileSize() throws ParseException {
		return (this.fileSize != null) ? parseHRNumberAsInt(this.fileSize) : parseHRNumberAsInt("100MiB");
	}

	public Integer resolveFileCount() {
		return (this.fileCount != null) ? this.fileCount : 25;
	}

	public Boolean resolveFileAppend() {
		return (this.appendFile != null) ? this.appendFile : true;
	}

	public Level resolveLoggingLevel() {
		return parseLoggingLevel(this.getLoggingLevel());
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
