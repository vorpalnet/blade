package org.vorpal.blade.framework.v3.config;

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

import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
//public class AttributeSelector extends Selector{
@JsonPropertyOrder({ "id", "description", "attribute", "pattern", "expression", "dialog", "additionalExpressions" })
public class AttributeSelector implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum DialogType {
		origin, destination
	};

	public String id; // optional for JSON references
	public String description; // optional for human readable descriptions
	public String attribute; // location of the key data, like in the 'To' header
	public Pattern _pattern; // regular expression using capturing groups to parse the key data
	public String expression; // replacement pattern, like ${ucid} to format the key data
	public DialogType dialog; // apply attributes to either origin or destination dialog (SipSession)
	public Map<String, String> additionalExpressions;

	public DialogType getDialog() {
//
//		if (dialog == null) {
//			return DialogType.origin;
//		} else {
//			return dialog;
//		}

		return dialog;
	}

	@JsonPropertyDescription("apply SipSession attributes to either origin or destination dialog")
	public AttributeSelector setDialog(DialogType dialog) {
		this.dialog = dialog;
		return this;
	}

	public Map<String, String> getAdditionalExpressions() {
		return additionalExpressions;
	}

	public AttributeSelector setAdditionalExpressions(Map<String, String> additionalExpressions) {
		this.additionalExpressions = additionalExpressions;
		return this;
	}

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
						sipLogger.severe(request,
								"AttributeSelector - No content in message body. Check configuration.");
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

					// create additional attributes that
					if (additionalExpressions != null && additionalExpressions.size() > 0) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
							String attrValue = Configuration.resolveVariables(attrsKey.attributes, entry.getValue());
							additionalAttributes.put(entry.getKey(), attrValue);
						}
						attrsKey.attributes.putAll(additionalAttributes);
					}

				}

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, "AttributeSelector " + //
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
			sipLogger.severe(request, "AttributeSelector; Unknown error; Check configuration file." + //
					"; matchResult=" + matchResult + //
					", selector id=" + this.getId() + //
					", attribute=" + this.getAttribute() + //
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
