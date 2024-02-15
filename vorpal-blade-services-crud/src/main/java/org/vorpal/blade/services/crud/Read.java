package org.vorpal.blade.services.crud;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Read {

	public String id;

	private Pattern pattern;

	public String attribute;
	public String expression;

	public Read() {

	}

	public Read(String attribute, String expression) {
		this.attribute = attribute;
		this.expression = expression;

		pattern = Pattern.compile(expression);

	}

	public void process(Map<String, String> map, SipServletMessage msg, Map<String, String> output)
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

//				sipLogger.finer(msg,"name=" + name + ", value='" + value + "'");

			}

		}

	}

	public static void main(String[] args) {

		String z = "\\<(?<name>[a-zA-Z0-9]+)\\>";

		// good
		String strPattern = "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*[;]*(?<uriparams>[^>]*)[>;]*(?<addrparams>.*)";

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
