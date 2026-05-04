package org.vorpal.blade.framework.v2.config;

import java.io.IOException;
import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/**
 * Defines a selector for extracting keys from SIP messages using regex patterns.
 */
@JsonPropertyOrder({ "id", "description", "attribute", "pattern", "expression" })
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Selector implements Serializable {
	private static final long serialVersionUID = 1L;
	protected String id;
	protected String description;
	protected String attribute;
	protected String pattern;
	protected String expression;

	@JsonIgnore
	private transient Pattern compiledPattern;

	@JsonIgnore
	private static final Pattern groupNameExtractor = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

	public Selector() {
	}

	public Selector(String id, String attribute, String pattern, String expression) {
		this.setId(id);
		this.setAttribute(attribute);
		this.setPattern(pattern);
		this.setExpression(expression);
	}

	@JsonPropertyDescription("Unique identifier for this selector")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Description of this selector's purpose")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("SIP message attribute to extract, e.g. From, To, Request-URI")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regular expression replacement pattern, e.g. ${user}, applied to extracted groups")
	public String getExpression() {
		return expression;
	}

	public void setExpression(String expression) {
		this.expression = expression;
	}

	@JsonPropertyDescription("Regular expression with named capturing groups for parsing the SIP attribute value")
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
	}

	public RegExRoute findKey(SipServletRequest request) {
		if (request == null) {
			return null;
		}

		Logger sipLogger = SettingsManager.getSipLogger();
		RegExRoute regexRoute = null;
		String key = null;
		String header = null;
		boolean matchResult = false;
		String value = null;

		try {

			// jwm - find named groups, useful later; see AttributeSelector
			// Map<String, String> namedGroups = new HashMap<>();

//			sipLogger.finer(request, "attribute=" + attribute);

			switch (attribute) {
			case "Content":
			case "content":
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
						sipLogger.warning(request,
								"Selector.findKey - No content in message body. Check configuration.");
					}
				} catch (IOException e) { // this should never happen
					SettingsManager.getSipLogger().logStackTrace(e);
					return null;
				}
				break;

			case "Request-URI":
			case "requestURI":
				header = request.getRequestURI().toString();
				break;

			case "Origin-IP":
			case "OriginIP":
			case "originIP":
				header = request.getRemoteAddr();

				if (header == null) { // test case only
					header = "127.0.0.1";
				}

				break;
			default:
				header = request.getHeader(attribute);
			}

//			sipLogger.finer(request, "header=" + header);

			if (header != null) {

				Matcher matcher = compiledPattern.matcher(header);

				value = (attribute.matches("Content")) ? "[...]" : header;

//				sipLogger.finer(request, "value=" + value);

				if (matcher.matches()) {
					matchResult = true;
					key = matcher.replaceAll(expression);
//					sipLogger.finer(request, "key=" + key);
				}

				if (key != null) {
					regexRoute = new RegExRoute();
					regexRoute.header = header;
					regexRoute.key = key;
					regexRoute.matcher = matcher;
					regexRoute.selector = this;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = groupNameExtractor.matcher(this.pattern);
					String __name;

					while (m.find()) {
						__name = m.group("name");
						if (__name != null) {
							groups.add(__name);
						}
					}

					// a__ variables. yucky! clean this up later.
					// This builds regex named group pairs for use later
					Matcher a__matcher = compiledPattern.matcher(header);
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
					sipLogger.finer(request, "Selector.findKey id=" + this.getId() + //
							", attribute=" + this.getAttribute() + //
							", value=" + value + //
							", matchResult=" + matchResult + //
							", key=" + key);
				}

			} else {
				// jwm - testing
				// sipLogger.severe(request, "No header found: " + attribute);
			}

		} catch (Exception e) {
			sipLogger.getParent().severe("Selector.findKey Error: " + e.getMessage());
			sipLogger.severe(request, request.toString());
			sipLogger.logStackTrace(e);
			sipLogger.severe(request, "Selector id=" + this.getId() + //
					", attribute=" + this.getAttribute() + //
					", value=" + value + //
					", matchResult=" + matchResult + //
					", key=" + key + //
					", pattern=" + this.getPattern() + //
					", expression=" + this.getExpression());
			throw e;
		}

		return regexRoute;
	}

}
