package org.vorpal.blade.framework.config;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Selector {

	private String id; // optional for JSON references
	private String description; // optional for human readable descriptions
	private String attribute; // location of the key data, like in the 'To' header
	private Pattern pattern; // regular expression using capturing groups to parse the key data
	private String expression; // replacement pattern, like $1 to format the key data

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
		return pattern.toString();
	}

	public void setPattern(String pattern) {
		this.pattern = Pattern.compile(pattern);
	}

	public RegExRoute findKey(SipServletRequest request) {

		RegExRoute regexRoute = null;
		String key = null;
		String header = null;

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
				}
			} catch (IOException e) {
				SettingsManager.getSipLogger().severe(request,
						"Invalid content type, unable to convert to Java String. This should never happen.");
				SettingsManager.getSipLogger().severe(e);
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

		Matcher matcher = pattern.matcher(header);

		if (matcher.matches()) {
			key = matcher.replaceAll(expression);
		}

		if (key != null) {
			regexRoute = new RegExRoute();
			regexRoute.header = header;
			regexRoute.key = key;
			regexRoute.matcher = matcher;
			regexRoute.selector = this;
		}

		SettingsManager.getSipLogger().finer(request, "Selector... attribute: " + attribute + "; value: " + header
				+ "; pattern: " + this.getPattern() + "; key: " + key + "; regexRoute: " + regexRoute);

		return regexRoute;
	}

}
