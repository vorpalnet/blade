package org.vorpal.blade.services.crud.rules;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public abstract class Rule implements Serializable {
	public enum MessageType {
		request, response
	};

	public enum MethodType {
		register, invite, ack, bye, cancel, update, refer, prack, subscribe, notify, publish, message, info, options
	};

	public enum ResponseType {
		provisional, successful, redirection, failure
	};

	public class AttributeValuePairs extends HashMap<String, LinkedList<String>> {
	}

	private AttributeValuePairs avps;

	public String id;
	public String description;

	public MessageType message;
	public MethodType method;
	public ResponseType response;

	public abstract void process(Map<String, String> map, SipServletMessage msg)
			throws IOException, ServletParseException;
	
	public static String resolveVariables(Map<String, String> map, String inputString) {
		int index;
		String variable;
		String key;
		String _value;
		String outputString = new String(inputString);
		while ((index = outputString.indexOf("${")) >= 0) {
			variable = outputString.substring(index, outputString.indexOf("}") + 1);
			key = variable.substring(2, variable.length() - 1);
			_value = map.get(key);
			_value = (_value != null) ? _value : "null";
			outputString = outputString.replace(variable, _value);
		}

		return outputString;
	}

}
