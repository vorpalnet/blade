package org.vorpal.blade.services.crud2.rules;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Read extends Rule implements Serializable {

	public String id;

	private Pattern pattern;

	public String attribute;
	private String expression;

	public String getId() {
		return id;
	}

	public Read setId(String id) {
		this.id = id;
		return this;
	}

	public String getAttribute() {
		return attribute;
	}

	public Read setAttribute(String attribute) {
		this.attribute = attribute;
		return this;
	}

	public String getExpression() {
		return expression;
	}

	public Read setExpression(String expression) {
		this.expression = expression;
		pattern = Pattern.compile(expression);
		return this;
	}

	public Read() {
		// do nothing;
	}

	public Read(String attribute, String expression) {
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

		LinkedList<String> groups = new LinkedList<>();

		Matcher m = pattern.matcher(expression);
		while (m.find()) {
			groups.add(m.group("name"));
		}

		Matcher matcher = pattern.matcher(header);
		boolean matchFound = matcher.find();
		if (matchFound) {
			String name, value;
			Iterator<String> itr = groups.iterator();
			while (itr.hasNext()) {
				name = itr.next();
				value = matcher.group(name);
				map.put(name, value);
			}

		}

	}

	public static void main(String[] args) {

		String z = "\\<(?<name>[a-zA-Z0-9]+)\\>";

		// good
		String strPattern = Configuration.SIP_ADDRESS_PATTERN;

		String[] urls = { //
				"sip:alice@vorpal.org", //
				"sip:alice@vorpal.org:5060", //
				"sip:alice@vorpal.org:5060;transport=tcp", //
				"<sip:alice@vorpal.org>", //
				"\"Alice\" <sip:alice@vorpal.org>", //
				"\"Alice\" <sip:alice@vorpal.org:5060>", //
				"\"Alice\" <sip:alice@vorpal.org:5060;transport=tcp>", //
				"\"Alice\" <sip:alice@vorpal.org:5060;transport=tcp>;param=one", //
		};

		LinkedList<String> groups = new LinkedList<>();

		Pattern p = Pattern.compile(z);

		Matcher m = p.matcher(strPattern);
		while (m.find()) {
			groups.add(m.group("name"));
		}

		Pattern pattern = Pattern.compile(strPattern);

		Matcher matcher;

		for (String url : urls) {
			System.out.println("\n" + url);
			matcher = pattern.matcher(url);
			boolean matchFound = matcher.find();
			if (matchFound) {
				String name, value;
				Iterator<String> itr = groups.iterator();
				while (itr.hasNext()) {

					name = itr.next();
					value = matcher.group(name);
					System.out.println("name=" + name + ", value='" + value + "'");

				}

			}
		}

	}

}
