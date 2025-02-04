package org.vorpal.blade.framework.v3.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class AttributeSelector {
	private String id; // optional for JSON references
	private String description; // optional for human readable descriptions
	private String attribute; // location of the key data, like in the 'To' header
	private Pattern _pattern; // regular expression using capturing groups to parse the key data
	private String expression; // replacement pattern, like $1 to format the key data
	private Map<String, String> additionalExpressions;

	public Map<String, String> getAdditionalExpressions() {
		return additionalExpressions;
	}

	public AttributeSelector setAdditionalExpressions(Map<String, String> additionalExpressions) {
		this.additionalExpressions = additionalExpressions;
		return this;
	}

	public AttributeSelector addAdditionalExpression(String attributeName, String attributeExpression) {
		additionalExpressions.put(attributeName, attributeExpression);
		return this;
	}

	@JsonIgnore
	private Pattern _p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

	@JsonIgnore
	private String _strPattern;

	public AttributeSelector() {
	}

	public AttributeSelector(String id, String attribute, String pattern, String expression) {
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

	public AttributesKey findKey(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();
		AttributesKey attrsKey = null;
//		RegExRoute regexRoute = null;
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

				if (header == null) {
					header = (String) request.getSession().getAttribute(attribute);
				}

			}

			sipLogger.finer(request, "header name=" + attribute + ", value=" + header);

			if (header != null) {

				Matcher matcher = _pattern.matcher(header);

				value = (attribute.matches("Content")) ? "[...]" : header;

				sipLogger.finer(request, "value=" + value);

				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
					sipLogger.finer(request, "key=" + key);
				}

				if (matchResult) {

					attrsKey = new AttributesKey();
					attrsKey.key = key;

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
//								regexRoute.attributes.put(a__name, a__value);
								attrsKey.attributes.put(a__name, a__value);

								sipLogger.finer(request,
										"regexRoute.attributes name=" + a__name + ", value=" + a__value);

							}
						}
					}

					// create additional attributes that
					if (additionalExpressions != null && additionalExpressions.size() > 0) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
							String attrValue = Configuration.resolveVariables(attrsKey.attributes, expression);
							additionalAttributes.put(entry.getKey(), attrValue);
						}
						attrsKey.attributes.putAll(additionalAttributes);
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

		return attrsKey;
	}

}
