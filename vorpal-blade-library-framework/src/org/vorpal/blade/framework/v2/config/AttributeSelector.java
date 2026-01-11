package org.vorpal.blade.framework.v2.config;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
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
 * Selector for extracting session attributes from SIP messages with support for
 * dialog association.
 */
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonPropertyOrder({ "id", "description", "attribute", "pattern", "expression", "dialog", "additionalExpressions" })
public class AttributeSelector implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum DialogType {
		origin, destination
	};

	private String id; // optional for JSON references
	private String description; // optional for human readable descriptions
	private String attribute; // location of the key data, like in the 'To' header
	private Pattern _pattern; // regular expression using capturing groups to parse the key data
	private String expression; // replacement pattern, like ${ucid} to format the key data
	private DialogType dialog; // apply attributes to either origin or destination dialog (SipSession)
	private Map<String, String> additionalExpressions;

	/**
	 * Default constructor for JSON deserialization.
	 */
	public AttributeSelector() {
	}

	/**
	 * Constructs an AttributeSelector with the specified parameters.
	 *
	 * @param id the unique identifier for this selector
	 * @param attribute the SIP message attribute to extract (e.g., "To", "From", "body")
	 * @param pattern the regular expression pattern with named capturing groups
	 * @param expression the replacement expression using captured group references
	 */
	public AttributeSelector(String id, String attribute, String pattern, String expression) {
		this.setId(id);
		this.setAttribute(attribute);
		this.setPattern(pattern);
		this.setExpression(expression);
	}

	/**
	 * Returns the dialog type indicating whether attributes should be applied
	 * to the origin or destination SIP session.
	 *
	 * @return the dialog type, or null if not specified
	 */
	public DialogType getDialog() {
		return dialog;
	}

	/**
	 * Sets the dialog type indicating whether attributes should be applied
	 * to the origin or destination SIP session.
	 *
	 * @param dialog the dialog type (origin or destination)
	 * @return this AttributeSelector for method chaining
	 */
	@JsonPropertyDescription("apply SipSession attributes to either origin or destination dialog")
	public AttributeSelector setDialog(DialogType dialog) {
		this.dialog = dialog;
		return this;
	}

	/**
	 * Returns the map of additional expressions that define derived attributes
	 * computed from the captured groups.
	 *
	 * @return the additional expressions map, or null if not configured
	 */
	public Map<String, String> getAdditionalExpressions() {
		return additionalExpressions;
	}

	/**
	 * Sets the map of additional expressions that define derived attributes
	 * computed from the captured groups.
	 *
	 * @param additionalExpressions the additional expressions map
	 * @return this AttributeSelector for method chaining
	 */
	public AttributeSelector setAdditionalExpressions(Map<String, String> additionalExpressions) {
		this.additionalExpressions = additionalExpressions;
		return this;
	}

	/**
	 * Adds a single additional expression that defines a derived attribute.
	 *
	 * @param attributeName the name of the derived attribute
	 * @param attributeExpression the expression to compute the attribute value
	 * @return this AttributeSelector for method chaining
	 */
	public AttributeSelector addAdditionalExpression(String attributeName, String attributeExpression) {

		if (this.additionalExpressions == null) {
			this.additionalExpressions = new HashMap<>();
		}

		additionalExpressions.put(attributeName, attributeExpression);
		return this;
	}

	@JsonIgnore
	private Pattern _p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

	@JsonIgnore
	private String _strPattern;

	/**
	 * Returns the unique identifier for this selector, used for JSON references.
	 *
	 * @return the selector ID
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the unique identifier for this selector, used for JSON references.
	 *
	 * @param id the selector ID
	 */
	public void setId(String id) {
		this.id = id;
	}

	/**
	 * Returns the human-readable description of this selector.
	 *
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Sets the human-readable description of this selector.
	 *
	 * @param description the description
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * Returns the SIP message attribute to extract data from.
	 * Supported values include: "To", "From", "body", "Content", "ruri", "Request-URI", "remoteIP",
	 * or any standard SIP header name.
	 *
	 * @return the attribute name
	 */
	public String getAttribute() {
		return attribute;
	}

	/**
	 * Sets the SIP message attribute to extract data from.
	 * Supported values include: "To", "From", "body", "Content", "ruri", "Request-URI", "remoteIP",
	 * or any standard SIP header name.
	 *
	 * @param attribute the attribute name
	 */
	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	/**
	 * Returns the replacement expression used to format the extracted key.
	 * The expression may contain references to named capturing groups.
	 *
	 * @return the replacement expression
	 */
	public String getExpression() {
		return expression;
	}

	/**
	 * Sets the replacement expression used to format the extracted key.
	 * The expression may contain references to named capturing groups.
	 *
	 * @param expression the replacement expression
	 */
	public void setExpression(String expression) {
		this.expression = expression;
	}

	/**
	 * Returns the regular expression pattern string.
	 *
	 * @return the pattern string
	 */
	public String getPattern() {
		return _pattern.toString();
	}

	/**
	 * Sets the regular expression pattern with named capturing groups.
	 * The pattern is compiled with DOTALL flag to allow matching across lines.
	 *
	 * @param pattern the regular expression pattern
	 */
	public void setPattern(String pattern) {
		this._strPattern = pattern;
		this._pattern = Pattern.compile(pattern, Pattern.DOTALL);
	}

	/**
	 * Extracts attributes from the SIP request using the configured pattern.
	 * Parses the specified attribute (header, body, URI, etc.) and applies
	 * the regular expression to extract named capturing groups.
	 *
	 * @param request the SIP request to extract attributes from
	 * @return an AttributesKey containing the extracted key and attributes,
	 *         or null if the request is null or pattern does not match
	 */
	public AttributesKey findKey(SipServletRequest request) {
		if (request == null) {
			return null;
		}

		Logger sipLogger = SettingsManager.getSipLogger();
		AttributesKey attrsKey = null;
		String key = null;
		String header = null;
		boolean matchResult = false;
		String value = null;

		try {
			switch (attribute) {
			case "body":
			case "Body":
			case "content":
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
						sipLogger.severe(request,
								"AttributeSelector.findKey - No content in message body. Check configuration.");
					}
				} catch (IOException e) { // this should never happen
					SettingsManager.getSipLogger().logStackTrace(e);
					return null;
				}
				break;

			case "ruri":
			case "Ruri":
			case "RURI":
			case "requestURI":
			case "RequestURI":
			case "Request-URI":
				header = request.getRequestURI().toString();
				break;

			case "remoteIP":
			case "RemoteIP":
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

			if (header != null) {

				Matcher matcher = _pattern.matcher(header);

				value = (attribute.matches("Content")) ? "[...]" : header;

				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
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
								attrsKey.attributes.put(a__name, a__value);
							}
						}
					}

					// use any additional attributes in the selector

					if (additionalExpressions != null && !additionalExpressions.isEmpty()) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {

							// make sure not to overwrite existing attributes
							if (!attrsKey.attributes.containsKey(entry.getKey())) {
								String attrValue = Configuration.resolveVariables(attrsKey.attributes,
										entry.getValue());
								additionalAttributes.put(entry.getKey(), attrValue);
							}

						}

						attrsKey.attributes.putAll(additionalAttributes);
					}

				}

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, "AttributeSelector.findKey - " + //
							"- matchResult=" + matchResult + //
							", selector id=" + this.getId() + //
							", attribute=" + this.getAttribute() + //
							", value=" + value + //
							", pattern=" + _strPattern + //
							", expression=" + expression + //
							", key=" + key);
				}

			}

		} catch (Exception e) {
			sipLogger.severe(request, "AttributeSelector.findKey - Error; Check configuration file." + //
					"; matchResult=" + matchResult + //
					", selector id=" + id + //
					", attribute=" + attribute + //
					", value=" + value + //
					", pattern=" + _strPattern + //
					", expression=" + expression + //
					", key=" + key);
			sipLogger.severe(request, request.toString());
			sipLogger.logStackTrace(request, e);
		}

		return attrsKey;
	}

}
