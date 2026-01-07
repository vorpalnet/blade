package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Base configuration class with logging, session parameters, and utility methods.
 */
public class Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String SIP_ADDRESS_PATTERN = "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";

	// Maximum iterations to prevent infinite loops in resolveVariables
	private static final int MAX_VARIABLE_RESOLUTION_ITERATIONS = 25;

	@JsonPropertyDescription("Logging parameters")
	protected LogParameters logging;

	@JsonPropertyDescription("Session parameters")
	protected SessionParameters session;

	private final static long SECONDS_FACTOR = 1;
	private final static long MINUTES_FACTOR = 60 * SECONDS_FACTOR;
	private final static long HOURS_FACTOR = 60 * MINUTES_FACTOR;
	private final static long DAYS_FACTOR = 24 * HOURS_FACTOR;

	public static int parseHRDurationAsSeconds(String humanReadable) throws ParseException {
		float value, ret;
		String factor;

		try {

			Pattern pattern = Pattern.compile("([0-9\\.]+)[ ]*((?i)[smhd])*(.*)");
			Matcher match = pattern.matcher(humanReadable);

			if (!match.matches()) {

				throw new ParseException("Wrong format", 0);

			} else {

				ret = Float.parseFloat(match.group(1));
				factor = match.group(2);
				if (factor == null) {
					value = ret;
				} else {
					switch (factor) {
					case "s":
					case "S":
						value = ret;
						break;
					case "m":
					case "M":
						value = ret * MINUTES_FACTOR;
						break;
					case "h":
					case "H":
						value = ret * HOURS_FACTOR;
						break;
					case "d":
					case "D":
						value = ret * DAYS_FACTOR;
						break;
					default:
						value = ret;
						break;
					}
				}
			}

			return Math.round(value);
		} catch (Exception e) {
			// Log exception details for debugging configuration issues
			Callflow.getSipLogger().severe(e);
			throw new ParseException("Wrong number format in configuration. Example formats include: 1, 2s, 3m, 4h", 0);
		}
	}

	private final static long KB_FACTOR = 1000;
	private final static long KIB_FACTOR = 1024;
	private final static long MB_FACTOR = 1000 * KB_FACTOR;
	private final static long MIB_FACTOR = 1024 * KIB_FACTOR;
	private final static long GB_FACTOR = 1000 * MB_FACTOR;
	private final static long GIB_FACTOR = 1024 * MIB_FACTOR;

	public static int parseHRNumberAsInt(String humanReadable) throws ParseException {
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
			// Log exception details for debugging configuration issues
			Callflow.getSipLogger().severe(e);
			throw new ParseException(
					"Wrong number format in configuration. Example formats include: 1, 2KiB, 3KB, 4MiB, 5MB, 6GiB, or 7.5GB",
					0);
		}

	}

	public LogParameters getLogging() {
		return logging;
	}

	public Configuration setLogging(LogParameters logging) {
		this.logging = logging;
		return this;
	}

	public SessionParameters getSession() {
		return session;
	}

	public Configuration setSession(SessionParameters session) {
		this.session = session;
		return this;
	}

	@SuppressWarnings("el-syntax")
	public static String resolveVariables(Map<String, String> attributes, String expression) {
		int openIndex;
		int closeIndex;
		String variable;
		String key;
		String value;
		String outputString = new String(expression);

		Callflow.getSipLogger().finer(
				"Configuration.resolveVariables - begin... expression=" + expression + ", attributes=" + attributes);

		try {

			int counter = 0;
			while ((openIndex = outputString.indexOf("${")) >= 0) {
				counter++;
				closeIndex = outputString.indexOf("}", openIndex);
				variable = outputString.substring(openIndex, closeIndex + 1);

				key = variable.substring(2, variable.length() - 1);
				value = (String) attributes.get(key);

				if (value != null) { // leave it alone in case we need to call this method again
					outputString = outputString.replace(variable, value);
				} else {
					outputString = outputString.replace(variable, "?{" + key + "}");
				}

				if (counter >= MAX_VARIABLE_RESOLUTION_ITERATIONS) {
					Callflow.getSipLogger()
							.warning("Configuration.resolveVariables - INFINITE LOOP, CHECK CONFIGURATION, counter="
									+ counter + ", expression=" + expression + ", attributes=" + attributes);
					return expression;
				}

			}

		} catch (Exception ex) {
			Callflow.getSipLogger()
					.severe("Configuration.resolveVariables - " + ex.getClass().getSimpleName() + " " + ex.getMessage()
							+ ", CHECK CONFIGURATION, expression=" + expression + ", attributes=" + attributes);
			Callflow.getSipLogger().severe(ex);
			Callflow.getSipLogger().getParent()
					.severe("Configuration.resolveVariables - " + ex.getClass().getSimpleName() + " " + ex.getMessage()
							+ ", CHECK CONFIGURATION, expression=" + expression + ", attributes=" + attributes);
			return expression;
		}

		outputString = outputString.replace("?{", "${");

		Callflow.getSipLogger().finer("Configuration.resolveVariables - end. outputString=" + outputString);

		return outputString;
	}

	public static void main(String[] args) throws Exception {

		Logger sipLogger = LogManager.getLogger("BLADE");
		sipLogger.setUseParentHandlers(false);
		sipLogger.addHandler(new ConsoleHandler() {
			{
				setOutputStream(System.out);
			}
		});
		sipLogger.setLevel(Level.FINEST);
		sipLogger.getParent().setLevel(Level.FINEST);
		SettingsManager.setSipLogger(sipLogger);
		Callflow.setLogger(sipLogger);

		String output;
		String expression = "${proto}:${user}@10.119.127.147:${port};${uriparams}";
//		String expression = "${proto}:${user}@10.119.127.147:${port};${uriparams";

		Map<String, String> attributes = new HashMap<>();

		attributes.put("proto", "sip");
//		attributes.put("user", "jeff");
//		attributes.put("port", "5060");
		attributes.put("uriparams", "whatever=true");

		output = resolveVariables(attributes, expression);
		System.out.println("output=" + output);
	}

}
