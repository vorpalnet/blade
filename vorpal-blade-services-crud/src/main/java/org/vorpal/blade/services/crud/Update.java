package org.vorpal.blade.services.crud;

import java.io.IOException;
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

import com.bea.wcp.sip.engine.SipServletRequestAdapter;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Update {
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

	public void process(Map<String, String> map, SipServletMessage msg, Map<String, String> output)
			throws UnsupportedEncodingException, IOException, ServletParseException {

		_pattern = Pattern.compile(pattern);
		_p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

		SettingsManager.sipLogger.warning(msg, "Update.process...");

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

		SettingsManager.sipLogger.warning(msg, "header=" + header);

		LinkedList<String> groups = new LinkedList<>();

		Matcher m = _p.matcher(this.pattern);
		String __name;

		while (m.find()) {
			__name = m.group("name");

			if (__name != null) {
				SettingsManager.sipLogger.warning(msg, "adding group=" + __name);
				groups.add(__name);
			} else {
				SettingsManager.sipLogger.severe(msg, "group name is NULL!");
			}

		}

		SettingsManager.sipLogger.fine(msg, "port was: '" + map.get("port") + "'");

		if (null == map.get("port") || map.get("port").length() == 0) {
			map.put("port", "5060");
		} else {
			SettingsManager.sipLogger.fine(msg, "port is now: " + map.get("port"));
		}

		Matcher matcher = _pattern.matcher(header);
		boolean matchFound = matcher.find();
		if (matchFound) {
			String name, value;
			Iterator<String> itr = groups.iterator();
			while (itr.hasNext()) {
				name = itr.next();

				SettingsManager.sipLogger.warning(msg, "matching on group=" + name);

				value = matcher.group(name);

				map.put(name, value);
			}

		} else {

			SettingsManager.sipLogger.severe(msg, "No match found for header value: " + header);
		}

		// now we have the map

		String update = Create.resolveVariables(map, replacement);

		SettingsManager.sipLogger.warning(msg, "RequestURI: " + update);

		output.put(attribute, update);

	}

}
