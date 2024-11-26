package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.config.Configuration;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Create implements Serializable {
	public String id;
	public String attribute = null;
	public String value = null;

	public Create() {
	}

	public Create(String attribute, String value) {
		this.attribute = attribute;
		this.value = value;
	}

	public void process(Map map, SipServletMessage msg, Map output) {
		String _value = Configuration.resolveVariables(map, this.value);
		msg.setHeader(this.attribute, _value);
	}

	public static void main(String[] args) {
	}
}
