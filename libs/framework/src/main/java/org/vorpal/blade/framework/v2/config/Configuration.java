package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.text.ParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Base configuration class with logging, session parameters, and utility
 * methods.
 */
@JsonPropertyOrder({ "logging", "session", "analytics" })
public class Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String SIP_ADDRESS_PATTERN = "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";

	protected LogParameters logging = new LogParametersDefault();
	protected SessionParameters session = new SessionParameters();

	protected Analytics analytics;

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

	@JsonPropertyDescription("Logging parameters")
	public LogParameters getLogging() {
		return logging;
	}

	public Configuration setLogging(LogParameters logging) {
		this.logging = logging;
		return this;
	}

	@JsonPropertyDescription("Session parameters")
	public SessionParameters getSession() {
		return session;
	}

	public Configuration setSession(SessionParameters session) {
		this.session = session;
		return this;
	}

	/// `${var}` substitution against an arbitrary attribute map.
	///
	/// Delegates to [Context#substitute], which iteratively re-resolves
	/// up to its configured cap, honours the reserved meta-variables
	/// (`${now}`, `${now:fmt}`, `${uuid}`), and falls back to environment
	/// variables and system properties before leaving a placeholder
	/// literal. Behaviour for the simple "lookup a name in `attributes`"
	/// case is unchanged from the historical implementation.
	public static String resolveVariables(Map<String, String> attributes, String expression) {
		return Context.substitute(expression, attributes);
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
