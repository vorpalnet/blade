package org.vorpal.blade.services.crud;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class RuleSet implements Serializable{
	public String id;

	public Map<String, String> map = new HashMap<>();
	public List<Rule> rules = new LinkedList<>();
	
	public Map<String, String> output = new HashMap<>();
	
	
	
	public RuleSet() {
		
		// fix this in the future
		map.put("port", "5060");
		
	}
	
	public void process(SipServletMessage msg) throws UnsupportedEncodingException, ServletParseException, IOException {
		for(Rule rule : rules) {
			rule.process(map, msg, output);
		}
	}
	
	
//	public void process() throws UnsupportedEncodingException, ServletParseException, IOException {
//		
//		for(Rule rule : rules) {
//			rule.process(map, msg, output);
//		}
//
//	}
	
	

}
