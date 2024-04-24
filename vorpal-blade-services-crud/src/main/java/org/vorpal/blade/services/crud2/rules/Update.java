package org.vorpal.blade.services.crud2.rules;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Update extends Rule implements Serializable {
	private Pattern _pattern, _p;

	public String id;
	public String attribute;
	public String replacement;
	public String pattern;

	public Update() {
	}

	public Update(String attribute, String pattern, String replacement) {
		this.attribute = attribute;
		this.pattern = pattern;
		this.replacement = replacement;

		System.out.println("pattern=" + pattern);

	}

//	public void process(Map<String, String> map, SipServletMessage msg, Map<String, String> output)
//			throws UnsupportedEncodingException, IOException, ServletParseException {

	public void process(Map<String, String> map, SipServletMessage msg)
			throws UnsupportedEncodingException, IOException, ServletParseException {

		_pattern = Pattern.compile(pattern);
		_p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

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
		Matcher m = _p.matcher(this.pattern);
		String __name;

		while (m.find()) {
			__name = m.group("name");

			if (__name != null) {
				groups.add(__name);
			}

		}

//		// fix the port problem
//		SettingsManager.sipLogger.fine(msg, "port was: '" + map.get("port") + "'");
//		if (null == map.get("port") || map.get("port").length() == 0) {
//			map.put("port", "5060");
//		} else {
//			SettingsManager.sipLogger.fine(msg, "port is now: " + map.get("port"));
//		}

		Matcher matcher = _pattern.matcher(header);
		boolean matchFound = matcher.find();
		if (matchFound) {
			String name, value;
			Iterator<String> itr = groups.iterator();
			while (itr.hasNext()) {
				name = itr.next();

				value = matcher.group(name);
				if (value != null && value.length() > 0) {
					map.put(name, value);
				}
			}
		}

		// now we have the map

		String update = Create.resolveVariables(map, replacement);

		// output.put(attribute, update);
		map.put(attribute, update);

//		SettingsManager.sipLogger.warning(msg, "\nUpdate final map...");
//		for (String key : map.keySet()) {
//			SettingsManager.sipLogger.warning(msg, "key: " + key + ", value: " + map.get(key));
//		}

//		SettingsManager.sipLogger.warning(msg, "\nUpdate final output...");
//		for (String key : output.keySet()) {
//			SettingsManager.sipLogger.warning(msg, "key: " + key + ", value: " + map.get(key));
//		}

	}

}
