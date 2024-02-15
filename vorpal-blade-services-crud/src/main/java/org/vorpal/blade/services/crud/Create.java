package org.vorpal.blade.services.crud;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.sip.SipServletMessage;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Create {
	public String id;
	public String attribute = null; // aka header, body, etc.
	public String value = null; // may contain ${variables}

	public Create() {

	}

	public Create(String attribute, String value) {
		this.attribute = attribute;
		this.value = value;
	}

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

	public void process(Map<String, String> map, SipServletMessage msg, Map<String, String> output) {
		String _value = resolveVariables(map, this.value);
		msg.setHeader(attribute, _value);
	}

	public static void main(String[] args) {
		// TODO Auto-generated method stub

	}

}
