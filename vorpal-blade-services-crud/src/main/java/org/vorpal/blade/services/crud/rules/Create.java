package org.vorpal.blade.services.crud.rules;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Create extends Rule implements Serializable{
	public String attribute = null; // aka header, body, etc.
	public String value = null; // may contain ${variables}

	public Create() {
	}

	public Create(String attribute, String value) {
		this.attribute = attribute;
		this.value = value;
	}

	public void process(Map<String, String> map, SipServletMessage msg, Map<String, String> output) {
		String _value = resolveVariables(map, this.value);
		msg.setHeader(attribute, _value);
	}

	public static void main(String[] args) {
		// TODO Auto-generated method stub

	}

	@Override
	public void process(Map<String, String> map, SipServletMessage msg) throws IOException, ServletParseException {
		// TODO Auto-generated method stub
		
	}

}
