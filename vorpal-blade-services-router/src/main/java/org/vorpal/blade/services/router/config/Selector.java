package org.vorpal.blade.services.router.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Selector {

	private String id;
	private String description;
	private String attribute;
	private Pattern pattern;
	private String expression;

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
		System.out.println("Selector.findKey...");

		RegExRoute regexRoute = null;
		String key = null;
		String header = null;

		System.out.println("Selector.findKey.attribute: " + attribute);

		switch (attribute) {
		case "Request-URI":
			header = request.getRequestURI().toString();
			break;
		case "Remote-IP":
			header = request.getRemoteAddr();
			
			if(header==null) { //test case only
				header = "127.0.0.1";
			}
			
			break;
		default:
			header = request.getHeader(attribute);
		}

		System.out.println("Selector.findKey.header: " + header);

		Matcher matcher = pattern.matcher(header);
		System.out.println("Selector.findKey.matcher: " + matcher);

		if (matcher.matches()) {
			key = matcher.replaceAll(expression);
			System.out.println("Selector.findKey.key: " + key);
		} else {
			System.out.println("Selector.findKey no match!");
		}

		if (key != null) {
			regexRoute = new RegExRoute();
			regexRoute.header = header;
			regexRoute.key = key;
			regexRoute.matcher = matcher;
			regexRoute.selector = this;
		}

		return regexRoute;
	}

}
