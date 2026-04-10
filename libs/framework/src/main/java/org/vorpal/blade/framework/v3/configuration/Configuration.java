package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.KeepAliveParameters;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Base configuration class for v3 services.
///
/// Provides logging, session management, analytics, and session-key selectors.
/// Services extend this class (or [RouterConfiguration]) and add their own
/// treatment-specific fields.
@JsonPropertyOrder({ "logging", "sessionExpiration", "keepAlive", "sessionSelectors", "analytics" })
public class Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final String SIP_ADDRESS_PATTERN = "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";
	private static final int MAX_VARIABLE_RESOLUTION_ITERATIONS = 5;

	protected LogParameters logging;

	@JsonPropertyDescription("SipApplicationSession expiration in minutes")
	protected Integer sessionExpiration;

	protected KeepAliveParameters keepAlive;

	@JsonPropertyDescription("Selectors that create session (SipApplicationSession) index keys for REST API lookup")
	protected List<Selector> sessionSelectors;

	protected Analytics analytics;

	// ---------------------------------------------------------------
	// Duration / size parsing utilities
	// ---------------------------------------------------------------

	private static final long SECONDS_FACTOR = 1;
	private static final long MINUTES_FACTOR = 60 * SECONDS_FACTOR;
	private static final long HOURS_FACTOR = 60 * MINUTES_FACTOR;
	private static final long DAYS_FACTOR = 24 * HOURS_FACTOR;

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
			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				sipLogger.severe(e);
			}
			throw new ParseException("Wrong number format in configuration. Example formats include: 1, 2s, 3m, 4h", 0);
		}
	}

	private static final long KB_FACTOR = 1000;
	private static final long KIB_FACTOR = 1024;
	private static final long MB_FACTOR = 1000 * KB_FACTOR;
	private static final long MIB_FACTOR = 1024 * KIB_FACTOR;
	private static final long GB_FACTOR = 1000 * MB_FACTOR;
	private static final long GIB_FACTOR = 1024 * MIB_FACTOR;

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
			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				sipLogger.severe(e);
			}
			throw new ParseException(
					"Wrong number format in configuration. Example formats include: 1, 2KiB, 3KB, 4MiB, 5MB, 6GiB, or 7.5GB",
					0);
		}
	}

	// ---------------------------------------------------------------
	// Variable resolution
	// ---------------------------------------------------------------

	@SuppressWarnings("el-syntax")
	public static String resolveVariables(Map<String, String> attributes, String expression) {
		int openIndex;
		int closeIndex;
		String variable;
		String key;
		String value;
		String outputString = new String(expression);

		Logger sipLogger = SettingsManager.getSipLogger();

		if (sipLogger != null) {
			sipLogger.finer("Configuration.resolveVariables - begin... expression=" + expression + ", attributes=" + attributes);
		}

		try {
			int counter = 0;
			while ((openIndex = outputString.indexOf("${")) >= 0) {
				counter++;
				closeIndex = outputString.indexOf("}", openIndex);
				variable = outputString.substring(openIndex, closeIndex + 1);

				key = variable.substring(2, variable.length() - 1);
				value = attributes.get(key);

				if (value != null) {
					outputString = outputString.replace(variable, value);
				} else {
					outputString = outputString.replace(variable, "?{" + key + "}");
				}

				if (counter >= MAX_VARIABLE_RESOLUTION_ITERATIONS) {
					if (sipLogger != null) {
						sipLogger.warning("Configuration.resolveVariables - INFINITE LOOP, CHECK CONFIGURATION, counter="
								+ counter + ", expression=" + expression + ", attributes=" + attributes);
					}
					return expression;
				}
			}
		} catch (Exception ex) {
			if (sipLogger != null) {
				sipLogger.severe("Configuration.resolveVariables - " + ex.getClass().getSimpleName() + " " + ex.getMessage()
						+ ", CHECK CONFIGURATION, expression=" + expression + ", attributes=" + attributes);
				sipLogger.severe(ex);
			}
			return expression;
		}

		outputString = outputString.replace("?{", "${");

		if (sipLogger != null) {
			sipLogger.finer("Configuration.resolveVariables - end. outputString=" + outputString);
		}

		return outputString;
	}

	// ---------------------------------------------------------------
	// Getters / setters
	// ---------------------------------------------------------------

	@JsonPropertyDescription("Logging parameters")
	public LogParameters getLogging() {
		return logging;
	}

	public Configuration setLogging(LogParameters logging) {
		this.logging = logging;
		return this;
	}

	public Integer getSessionExpiration() {
		return sessionExpiration;
	}

	public Configuration setSessionExpiration(Integer sessionExpiration) {
		this.sessionExpiration = sessionExpiration;
		return this;
	}

	@JsonPropertyDescription("Keep-alive parameters")
	public KeepAliveParameters getKeepAlive() {
		return keepAlive;
	}

	public Configuration setKeepAlive(KeepAliveParameters keepAlive) {
		this.keepAlive = keepAlive;
		return this;
	}

	public List<Selector> getSessionSelectors() {
		return sessionSelectors;
	}

	public Configuration setSessionSelectors(List<Selector> sessionSelectors) {
		this.sessionSelectors = sessionSelectors;
		return this;
	}

	@JsonPropertyDescription("Analytics parameters")
	public Analytics getAnalytics() {
		return analytics;
	}

	public Configuration setAnalytics(Analytics analytics) {
		this.analytics = analytics;
		return this;
	}

}
