package org.vorpal.blade.framework.v2.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonPropertyOrder({"id", "description", "attribute", "pattern", "expression"})
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Selector {
	protected String id; // optional for JSON references
	protected String description; // optional for human readable descriptions
	protected String attribute; // location of the key data, like in the 'To' header
	protected Pattern _pattern; // regular expression using capturing groups to parse the key data
	protected String expression; // replacement pattern, like $1 to format the key data

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

			sipLogger.finer(request, "attribute=" + attribute);

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

			sipLogger.finer(request, "header=" + header);

			if (header != null) {

				Matcher matcher = _pattern.matcher(header);

				value = (attribute.matches("Content")) ? "[...]" : header;

				sipLogger.finer(request, "value=" + value);

				if (matcher.matches()) {
					matchResult = true;
					key = matcher.replaceAll(expression);
					sipLogger.finer(request, "key=" + key);
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

								sipLogger.finer(request,
										"regexRoute.attributes name=" + a__name + ", value=" + a__value);

							}
						}
					}

				}

				if (sipLogger.isLoggable(Level.FINER)) {
					if (matchResult == true) {
						sipLogger.finer(request, "Pattern match found, Selector id=" + this.getId() + //
								", attribute=" + this.getAttribute() + //
								", value=" + value + //
								", matchResult=" + matchResult + //
								", key=" + key);
					} else {
						sipLogger.finer(request, "No pattern match found, Selector id=" + this.getId() + //
								", attribute=" + this.getAttribute() + //
								", value=" + value + //
								", matchResult=" + matchResult + //
								", key=" + key + //
								", pattern=" + this.getPattern() + //
								", expression=" + this.getExpression());
					}
				}

			} else {
				// jwm - testing
				// sipLogger.severe(request, "No header found: " + attribute);
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

	public static void main4(String args[]) throws ServletParseException {

		SipServletRequest dummyRequest = new DummyRequest("INVITE", "19137774321", "18165551234");

		Selector toSelector = new Selector("toSelector", "To", Configuration.SIP_ADDRESS_PATTERN, "${user}");
		toSelector.setDescription("The user part of the To header");
		RegExRoute key = toSelector.findKey(dummyRequest);

	}

	public static void main3(String args[]) {
		String strPattern = ".*recorddn=[\\+]*(?<recorddn>[0-9]+).*";
		String strExpression = "${recorddn}";
		String key = null;
	}

	public static void main2(String args[]) {
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

	public static void main(String args[]) {
// "(.*?)"
// "([^"]*)"
// (\"[\w\s]+\")		 
//		String X_SIP_ADDRESS_PATTERN = "(?:[\"]*(?<name>.*)[\"*] )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)";
//		String X_SIP_ADDRESS_PATTERN = "^(?:[\"]*(?<name>.*)[\"*] )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";
//		String X_SIP_ADDRESS_PATTERN = "^(?:[\"]?(?<name>[a-zA-Z0-9 ]*)[\"]? )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";
//		String X_SIP_ADDRESS_PATTERN = "^(?:[\"]?(?<name>.*)[\\\"\\s]*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";
		String X_SIP_ADDRESS_PATTERN = "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)$";

		Map<String, String> attributes = new HashMap<>();

		String _strPattern = X_SIP_ADDRESS_PATTERN;

		System.out.println("Hello World!");

		String header;
		Boolean matchResult;
		String key = null;
		String expression;

		Pattern _p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

		Pattern _pattern = Pattern.compile(X_SIP_ADDRESS_PATTERN, Pattern.DOTALL);

//		header = "\"18165551234\" <sip:18165551234@192.168.1.157:5099>";
//		header = "18165551234 <sip:18165551234@192.168.1.157:5099>";
//		header = "Jeff McDonald 123 <sip:18165551234@192.168.1.157:5099>";
//		header = "\"Jeff & McDonald 123\" <sip:18165551234@192.168.1.157:5099>";
//		header = "Jeff & McDonald 123 <sip:18165551234@192.168.1.157:5099>";
//		header = "<sip:18165551234@192.168.1.157:5099>";
		header = "sip:18165551234@192.168.1.157:5099";
		expression = "${user}";
		expression = "name=${name}, proto=${proto}, user=${user}, host=${host}, port=${port}, uriparams=${uriparams}, addrparams=${addrparams}";

		LinkedList<String> groups = new LinkedList<>();
		Matcher m = _p.matcher(_strPattern);
		String __name;

		while (m.find()) {
			__name = m.group("name");
			if (__name != null) {
				groups.add(__name);
				System.out.println("adding name to group: " + __name);
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
					attributes.put(a__name, a__value);

					System.out.println("attributes name=" + a__name + ", value=" + a__value);
				}
			}
		}

		Matcher matcher = _pattern.matcher(header);

		if (matcher.matches()) {
			matchResult = true;
			key = matcher.replaceAll(expression);
		}
		System.out.println(key);

	}

}
