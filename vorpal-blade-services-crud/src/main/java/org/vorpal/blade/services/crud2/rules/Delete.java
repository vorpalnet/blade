package org.vorpal.blade.services.crud2.rules;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")

public class Delete extends Rule implements Serializable{
	public String id;

	private Pattern pattern;
	public String attribute;
	public String expression;

	public Delete() {

	}

	public Delete(String attribute, String expression) {
		this.attribute = attribute;
		this.expression = expression;

		pattern = Pattern.compile(expression);
	}

	public void process(Map<String, String> map, SipServletMessage msg)
			throws UnsupportedEncodingException, IOException {

		String header = null;

		switch (attribute) {

		case "Request-URI":
			header = ((SipServletRequest) msg).getRequestURI().toString();
			break;
		case "Content":
			if (msg.getContent() != null) {
				if (msg.getContent() instanceof String) {
					header = (String) msg.getContent();
				} else {
					byte[] content = (byte[]) msg.getContent();
					header = new String(content);
				}
			}
			break;

		default:
			header = msg.getHeader(attribute);

		}

		Matcher m = pattern.matcher(expression);
		if (m.find()) {

			switch (attribute) {

			case "Request-URI":
				header = ((SipServletRequest) msg).getRequestURI().toString();
				break;

			case "Content":
				msg.setContent(null, null); // does this work?
				break;

			default:
				msg.removeHeader(attribute);

			}

		}

	}

}
