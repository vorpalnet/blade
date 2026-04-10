package org.vorpal.blade.framework.v3.configuration;

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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

@JsonPropertyOrder({ "id", "description", "attribute", "pattern", "expression", "index", "dialog", "additionalExpressions" })
public class Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum DialogType {
		origin, destination
	}

	private String id;
	private String description;
	private String attribute;
	private String pattern;
	private String expression;
	private Boolean index = false;
	private DialogType dialog;
	private Map<String, String> additionalExpressions;

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

	@JsonPropertyDescription("Regular expression with named capturing groups for parsing the SIP attribute value")
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
	}

	@JsonPropertyDescription("Regular expression replacement pattern, e.g. ${user}, applied to extracted groups")
	public String getExpression() {
		return expression;
	}

	public void setExpression(String expression) {
		this.expression = expression;
	}

	@JsonPropertyDescription("When true, the extracted key is added as a SipApplicationSession index key for REST API lookup")
	public Boolean isIndex() {
		return index;
	}

	public Selector setIndex(Boolean index) {
		this.index = index;
		return this;
	}

	@JsonPropertyDescription("Apply SipSession attributes to either origin or destination dialog")
	public DialogType getDialog() {
		return dialog;
	}

	public Selector setDialog(DialogType dialog) {
		this.dialog = dialog;
		return this;
	}

	@JsonPropertyDescription("Additional named expressions for extracting multiple values from the attribute")
	public Map<String, String> getAdditionalExpressions() {
		return additionalExpressions;
	}

	public Selector setAdditionalExpressions(Map<String, String> additionalExpressions) {
		this.additionalExpressions = additionalExpressions;
		return this;
	}

	public Selector addAdditionalExpression(String attributeName, String attributeExpression) {
		if (this.additionalExpressions == null) {
			this.additionalExpressions = new HashMap<>();
		}
		additionalExpressions.put(attributeName, attributeExpression);
		return this;
	}

	public AttributesKey findKey(SipServletMessage message) {
		if (message == null || attribute == null) {
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

			case "status":
				if (message instanceof SipServletResponse) {
					header = Integer.toString(((SipServletResponse) message).getStatus());
				} else {
					sipLogger.warning(message, "No 'status' defined for " + message.getClass().getSimpleName());
				}
				break;

			case "reason":
			case "reasonPhrase":
				if (message instanceof SipServletResponse) {
					header = ((SipServletResponse) message).getReasonPhrase();
				} else {
					sipLogger.warning(message, "No 'reasonPhrase' defined for " + message.getClass().getSimpleName());
				}
				break;

			case "body":
			case "Body":
			case "content":
			case "Content":
				header = "";
				try {
					if (message.getContent() != null) {
						if (message.getContent() instanceof String) {
							header = (String) message.getContent();
						} else {
							byte[] content = (byte[]) message.getContent();
							header = new String(content);
						}
					} else {
						sipLogger.severe(message,
								"Selector.findKey - No content in message body. Check configuration.");
					}
				} catch (IOException e) {
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
				if (message instanceof SipServletRequest) {
					header = ((SipServletRequest) message).getRequestURI().toString();
				}
				break;

			case "remoteIP":
			case "RemoteIP":
			case "Remote-IP":
				header = message.getRemoteAddr();
				if (header == null) {
					header = "127.0.0.1";
				}
				break;

			default:
				header = message.getHeader(attribute);
				header = (header != null) ? header : (String) message.getSession().getAttribute(attribute);
			}

			if (header != null) {
				Matcher matcher = compiledPattern.matcher(header);
				value = (attribute.matches("Content")) ? "[...]" : header;
				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
				}

				if (matchResult) {
					attrsKey = new AttributesKey();
					attrsKey.key = key;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = groupNameExtractor.matcher(this.pattern);
					while (m.find()) {
						String name = m.group("name");
						if (name != null) {
							groups.add(name);
						}
					}

					Matcher groupMatcher = compiledPattern.matcher(header);
					if (groupMatcher.find()) {
						Iterator<String> itr = groups.iterator();
						while (itr.hasNext()) {
							String name = itr.next();
							String groupValue = groupMatcher.group(name);
							if (groupValue != null && groupValue.length() > 0) {
								attrsKey.attributes.put(name, groupValue);
							}
						}
					}

					if (additionalExpressions != null && !additionalExpressions.isEmpty()) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
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
					sipLogger.finer(message, "Selector.findKey" +
							" matchResult=" + matchResult +
							", selector id=" + this.getId() +
							", attribute=" + this.getAttribute() +
							", value=" + value +
							", pattern=" + pattern +
							", expression=" + expression +
							", key=" + key);
				}
			}

		} catch (Exception e) {
			sipLogger.severe(message, "Selector.findKey - Error; Check configuration file." +
					"; matchResult=" + matchResult +
					", selector id=" + id +
					", attribute=" + attribute +
					", value=" + value +
					", pattern=" + pattern +
					", expression=" + expression +
					", key=" + key);
			sipLogger.severe(message, message.toString());
			sipLogger.logStackTrace(message, e);
		}

		return attrsKey;
	}

	public AttributesKey findKey(SipServletContextEvent context) {
		if (context == null || attribute == null) {
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
			case "servletInfo":
				header = context.getSipServlet().getServletInfo();
				break;
			case "servletName":
				header = context.getSipServlet().getServletName();
				break;
			case "contextPath":
				header = context.getServletContext().getContextPath();
				break;
			case "servletContextName":
				header = context.getServletContext().getServletContextName();
				break;
			default:
				header = context.getServletContext().getInitParameter(attribute);
				header = (header != null) ? header : (String) context.getServletContext().getAttribute(attribute);
				header = (header != null) ? header : System.getenv(attribute);
				header = (header != null) ? header : System.getProperty(attribute);
			}

			if (header != null) {
				Matcher matcher = compiledPattern.matcher(header);
				value = (attribute.matches("Content")) ? "[...]" : header;
				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
				}

				if (matchResult) {
					attrsKey = new AttributesKey();
					attrsKey.key = key;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = groupNameExtractor.matcher(this.pattern);
					while (m.find()) {
						String name = m.group("name");
						if (name != null) {
							groups.add(name);
						}
					}

					Matcher groupMatcher = compiledPattern.matcher(header);
					if (groupMatcher.find()) {
						Iterator<String> itr = groups.iterator();
						while (itr.hasNext()) {
							String name = itr.next();
							String groupValue = groupMatcher.group(name);
							if (groupValue != null && groupValue.length() > 0) {
								attrsKey.attributes.put(name, groupValue);
							}
						}
					}

					if (additionalExpressions != null && !additionalExpressions.isEmpty()) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
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
					sipLogger.finer("Selector.findKey(context)" +
							" matchResult=" + matchResult +
							", selector id=" + this.getId() +
							", attribute=" + this.getAttribute() +
							", value=" + value +
							", pattern=" + pattern +
							", expression=" + expression +
							", key=" + key);
				}
			}

		} catch (Exception e) {
			sipLogger.severe("Selector.findKey(context) - Error; Check configuration file." +
					"; matchResult=" + matchResult +
					", selector id=" + id +
					", attribute=" + attribute +
					", value=" + value +
					", pattern=" + pattern +
					", expression=" + expression +
					", key=" + key);
			sipLogger.severe(Logger.serializeObject(context));
			sipLogger.logStackTrace(e);
		}

		return attrsKey;
	}

	private static final com.jayway.jsonpath.Configuration jsonConfiguration = com.jayway.jsonpath.Configuration
			.builder().jsonProvider(new JacksonJsonNodeJsonProvider()).mappingProvider(new JacksonMappingProvider())
			.build();

	public AttributesKey findKey(JsonNode jsonNode) {
		if (jsonNode == null || attribute == null) {
			return null;
		}

		Logger sipLogger = SettingsManager.getSipLogger();
		AttributesKey attrsKey = null;
		String key = null;
		String header = null;
		boolean matchResult = false;
		String value = null;

		try {
			DocumentContext documentContext = JsonPath.using(jsonConfiguration).parse(jsonNode);
			header = documentContext.read(attribute, String.class);

			if (header != null) {
				Matcher matcher = compiledPattern.matcher(header);
				value = (attribute.matches("content|body")) ? "[...]" : header;
				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
				}

				if (matchResult) {
					attrsKey = new AttributesKey();
					attrsKey.key = key;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = groupNameExtractor.matcher(this.pattern);
					while (m.find()) {
						String name = m.group("name");
						if (name != null) {
							groups.add(name);
						}
					}

					Matcher groupMatcher = compiledPattern.matcher(header);
					if (groupMatcher.find()) {
						Iterator<String> itr = groups.iterator();
						while (itr.hasNext()) {
							String name = itr.next();
							String groupValue = groupMatcher.group(name);
							if (groupValue != null && groupValue.length() > 0) {
								attrsKey.attributes.put(name, groupValue);
							}
						}
					}

					if (additionalExpressions != null && !additionalExpressions.isEmpty()) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
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
					sipLogger.finer("Selector.findKey(JsonNode)" +
							" matchResult=" + matchResult +
							", selector id=" + this.getId() +
							", attribute=" + this.getAttribute() +
							", value=" + value +
							", pattern=" + pattern +
							", expression=" + expression +
							", key=" + key);
				}
			}

		} catch (Exception e) {
			sipLogger.severe("Selector.findKey(JsonNode) - Error; Check configuration file." +
					"; matchResult=" + matchResult +
					", selector id=" + id +
					", attribute=" + attribute +
					", value=" + value +
					", pattern=" + pattern +
					", expression=" + expression +
					", key=" + key);
			sipLogger.logStackTrace(e);
		}

		return attrsKey;
	}

	public AttributesKey findKey(HttpServletRequest request) {
		return findKey(request, null);
	}

	public AttributesKey findKey(HttpServletRequest request, byte[] requestBody) {
		if (request == null || attribute == null) {
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
			case "method":
				header = request.getMethod();
				break;
			case "requestURI":
			case "RequestURI":
			case "Request-URI":
				header = request.getRequestURI();
				break;
			case "requestURL":
			case "RequestURL":
				header = request.getRequestURL().toString();
				break;
			case "queryString":
				header = request.getQueryString();
				break;
			case "contentType":
				header = request.getContentType();
				break;
			case "remoteIP":
			case "RemoteIP":
			case "Remote-IP":
				header = request.getRemoteAddr();
				break;
			case "body":
			case "content":
				if (requestBody != null) {
					header = new String(requestBody);
				}
				break;
			default:
				header = request.getHeader(attribute);
			}

			if (header != null) {
				Matcher matcher = compiledPattern.matcher(header);
				value = (attribute.matches("content|body")) ? "[...]" : header;
				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
				}

				if (matchResult) {
					attrsKey = new AttributesKey();
					attrsKey.key = key;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = groupNameExtractor.matcher(this.pattern);
					while (m.find()) {
						String name = m.group("name");
						if (name != null) {
							groups.add(name);
						}
					}

					Matcher groupMatcher = compiledPattern.matcher(header);
					if (groupMatcher.find()) {
						Iterator<String> itr = groups.iterator();
						while (itr.hasNext()) {
							String name = itr.next();
							String groupValue = groupMatcher.group(name);
							if (groupValue != null && groupValue.length() > 0) {
								attrsKey.attributes.put(name, groupValue);
							}
						}
					}

					if (additionalExpressions != null && !additionalExpressions.isEmpty()) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
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
					sipLogger.finer("Selector.findKey(HttpServletRequest)" +
							" matchResult=" + matchResult +
							", selector id=" + this.getId() +
							", attribute=" + this.getAttribute() +
							", value=" + value +
							", pattern=" + pattern +
							", expression=" + expression +
							", key=" + key);
				}
			}

		} catch (Exception e) {
			sipLogger.severe("Selector.findKey(HttpServletRequest) - Error; Check configuration file." +
					"; matchResult=" + matchResult +
					", selector id=" + id +
					", attribute=" + attribute +
					", value=" + value +
					", pattern=" + pattern +
					", expression=" + expression +
					", key=" + key);
			sipLogger.logStackTrace(e);
		}

		return attrsKey;
	}

	public AttributesKey findKey(HttpServletResponse response) {
		return findKey(response, null);
	}

	public AttributesKey findKey(HttpServletResponse response, byte[] responseBody) {
		if (response == null || attribute == null) {
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
			case "status":
				header = Integer.toString(response.getStatus());
				break;
			case "contentType":
				header = response.getContentType();
				break;
			case "body":
			case "content":
				if (responseBody != null) {
					header = new String(responseBody);
				}
				break;
			default:
				header = response.getHeader(attribute);
			}

			if (header != null) {
				Matcher matcher = compiledPattern.matcher(header);
				value = (attribute.matches("content|body")) ? "[...]" : header;
				matchResult = matcher.matches();

				if (matchResult && expression != null) {
					key = matcher.replaceAll(expression);
				}

				if (matchResult) {
					attrsKey = new AttributesKey();
					attrsKey.key = key;

					LinkedList<String> groups = new LinkedList<>();
					Matcher m = groupNameExtractor.matcher(this.pattern);
					while (m.find()) {
						String name = m.group("name");
						if (name != null) {
							groups.add(name);
						}
					}

					Matcher groupMatcher = compiledPattern.matcher(header);
					if (groupMatcher.find()) {
						Iterator<String> itr = groups.iterator();
						while (itr.hasNext()) {
							String name = itr.next();
							String groupValue = groupMatcher.group(name);
							if (groupValue != null && groupValue.length() > 0) {
								attrsKey.attributes.put(name, groupValue);
							}
						}
					}

					if (additionalExpressions != null && !additionalExpressions.isEmpty()) {
						Map<String, String> additionalAttributes = new HashMap<>();
						for (Entry<String, String> entry : this.additionalExpressions.entrySet()) {
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
					sipLogger.finer("Selector.findKey(HttpServletResponse)" +
							" matchResult=" + matchResult +
							", selector id=" + this.getId() +
							", attribute=" + this.getAttribute() +
							", value=" + value +
							", pattern=" + pattern +
							", expression=" + expression +
							", key=" + key);
				}
			}

		} catch (Exception e) {
			sipLogger.severe("Selector.findKey(HttpServletResponse) - Error; Check configuration file." +
					"; matchResult=" + matchResult +
					", selector id=" + id +
					", attribute=" + attribute +
					", value=" + value +
					", pattern=" + pattern +
					", expression=" + expression +
					", key=" + key);
			sipLogger.logStackTrace(e);
		}

		return attrsKey;
	}

}
