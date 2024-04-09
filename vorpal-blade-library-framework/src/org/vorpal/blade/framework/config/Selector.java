package org.vorpal.blade.framework.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Selector {

	private String id; // optional for JSON references
	private String description; // optional for human readable descriptions
	private String attribute; // location of the key data, like in the 'To' header
	private Pattern _pattern; // regular expression using capturing groups to parse the key data
	private String expression; // replacement pattern, like $1 to format the key data

	@JsonIgnore
	private Pattern _p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

	@JsonIgnore
	private String _strPattern;

	public Selector() {
	}

	public Selector(String id, String attribute, String pattern, String expression) {
		this.setId(id);
		this.setAttribute(attribute);
		this.setPattern(pattern);
		this.setExpression(expression);
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	public String getExpression() {
		return expression;
	}

	public void setExpression(String expression) {
		this.expression = expression;
	}

	public String getPattern() {
		return _pattern.toString();
	}

	public void setPattern(String pattern) {
		this._strPattern = pattern;
		this._pattern = Pattern.compile(pattern, Pattern.DOTALL);
	}

	public RegExRoute findKey(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();
		RegExRoute regexRoute = null;
		String key = null;
		String header = null;
		boolean matchResult = false;
		String value = null;

		try {

			// jwm - find named groups, useful later
			Map<String, String> namedGroups = new HashMap<>();

			switch (attribute) {
			case "Content":
				header = "";
				try {
					if (request.getContent() != null) {
						if (request.getContent() instanceof String) {
							header = (String) request.getContent();
						} else {
							byte[] content = (byte[]) request.getContent();
							header = new String(content);
						}
					} else {
						sipLogger.warning(request, "No content in message body. Check configuration.");
					}
				} catch (IOException e) { // this should never happen
					SettingsManager.getSipLogger().logStackTrace(e);
					return null;
				}
				break;

			case "Request-URI":
				header = request.getRequestURI().toString();
				break;

			case "Remote-IP":
				header = request.getRemoteAddr();

				if (header == null) { // test case only
					header = "127.0.0.1";
				}

				break;
			default:
				header = request.getHeader(attribute);
			}

			if (header != null) {

				Matcher matcher = _pattern.matcher(header);

				value = (attribute.matches("Content")) ? "[...]" : header;
				if (matcher.matches()) {
					matchResult = true;
					key = matcher.replaceAll(expression);
				}

				if (key != null) {
					regexRoute = new RegExRoute();
					regexRoute.header = header;
					regexRoute.key = key;
					regexRoute.matcher = matcher;
					regexRoute.selector = this;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = _p.matcher(this._strPattern);
					String __name;

					while (m.find()) {
						__name = m.group("name");
						if (__name != null) {
							groups.add(__name);
						}
					}

					// a__ variables. yucky! clean this up later.
					// This builds regex named group pairs for use later
					Matcher a__matcher = _pattern.matcher(header);
					boolean a__matchFound = a__matcher.find();
					if (a__matchFound) {
						String a__name, a__value;
						Iterator<String> a__itr = groups.iterator();
						while (a__itr.hasNext()) {
							a__name = a__itr.next();
							a__value = a__matcher.group(a__name);
							if (a__value != null && a__value.length() > 0) {
								regexRoute.attributes.put(a__name, a__value);
							}
						}
					}

				}

				if (sipLogger.isLoggable(Level.FINER)) {
					if (matchResult == true) {
						sipLogger.finer(request, "Selector id=" + this.getId() + //
								", attribute=" + this.getAttribute() + //
								", value=" + value + //
								", matchResult=" + matchResult + //
								", key=" + key);
					} else {
						sipLogger.severe(request, "Configuration error... No pattern match found.");
						sipLogger.finer(request, "Selector id=" + this.getId() + //
								", attribute=" + this.getAttribute() + //
								", value=" + value + //
								", matchResult=" + matchResult + //
								", key=" + key + //
								", pattern=" + this.getPattern() + //
								", expression=" + this.getExpression());
					}
				}

			} else {
				sipLogger.severe(request, "No header found: " + attribute);
			}

		} catch (Exception e) {
			sipLogger.severe(request, "Unknown error for Selector.findKey()");
			sipLogger.severe(request, "Selector id=" + this.getId() + //
					", attribute=" + this.getAttribute() + //
					", value=" + value + //
					", matchResult=" + matchResult + //
					", key=" + key + //
					", pattern=" + this.getPattern() + //
					", expression=" + this.getExpression());
			sipLogger.severe(request, request.toString());
			sipLogger.logStackTrace(e);
		}

		return regexRoute;
	}

	
	public static void main(String args[]) {
		
		/*
		 *     "id" : "Referred-By",
    "description" : "The user@host part of the Referred-By header",
    "attribute" : "Referred-By",
    "expression" : "${user}@${host}",
    "pattern" : "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)"
		 */
		
		
		/*
		 *   "id" : "Refer-To",
    "description" : "The user part of the Refer-To header",
    "attribute" : "Refer-To",
    "expression" : "${user}",
    "pattern" : "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)"
		 */
		
		
		
		String strPattern = ".*recorddn=[\\+]*(?<recorddn>[0-9]+).*";
		String strExpression = "${recorddn}";
		String key = null;
		
//		<sip:19977011234@vorpal.net>
//		<sip:bob@vorpal.net>
		
	}
	
	public static void main2(String args[]) {

		// "pattern": ".*recorddn=[\\+]*(?<recorddn>[0-9]+).*",
		// "expression": "${recorddn}"

		String strPattern = ".*recorddn=[\\+]*(?<recorddn>[0-9]+).*";
		String strExpression = "${recorddn}";
		String key = null;

		String strBody = //
				"\n" + // A newline (line feed) character
						"\r\n" + // A carriage-return character followed immediately by a newline character
						"\r" + // A standalone carriage-return character
						"\u0085" + // A next-line character
						"\u2028" + // A line-separator character
						"\u2029" + // A paragraph-separator character
						"yarp;booyah;recorddn=+18164388687;boonah;narp" + // actual text
						"\u2029" + // A paragraph-separator character
						"\u2028" + // A line-separator character
						"\u0085" + // A next-line character
						"\r" + // A standalone carriage-return character
						"\r\n" + // A carriage-return character followed immediately by a newline character
						"\n" // A newline (line feed) character
		;

//		Pattern pattern = Pattern.compile(strPattern); // Doesn't work;
		Pattern pattern = Pattern.compile(strPattern, //
				Pattern.CASE_INSENSITIVE | //
						Pattern.MULTILINE | //
						Pattern.DOTALL | //
						Pattern.UNICODE_CASE | //
						Pattern.CANON_EQ | //
						Pattern.UNIX_LINES | //
//						Pattern.LITERAL | // --This one is bad
						Pattern.UNICODE_CHARACTER_CLASS | //
						Pattern.COMMENTS);
		Matcher matcher = pattern.matcher(strBody);
		if (matcher.matches()) {
			key = matcher.replaceAll(strExpression);
		}

		System.out.println("Key: " + key);

	}

}
