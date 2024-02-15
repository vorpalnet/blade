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
public class Rule implements Serializable{
	public enum MessageType {
		REQUEST, RESPONSE
	};

	public enum MethodType {
		INVITE, ACK, BYE, CANCEL, REGISTER, OPTIONS, PRACK, SUBSCRIBE, NOTIFY, PUBLISH, INFO, UPDATE, REFER
	};

	private Map<String, String> map;

	public String id;
	public String description;

	public String method;

	public List<Create> create = new LinkedList<>();
	public List<Read> read = new LinkedList<>();
	public List<Update> update = new LinkedList<>();
	public List<Delete> delete = new LinkedList<>();

	public Rule() {

	}

//	public Rule(Map<String, String> map) {
//		this.map = map;
//	}

//	public void process(Map<String, String> map, SipServletMessage msg)
//			throws UnsupportedEncodingException, IOException, ServletParseException {
//
////		for (Read _read : read) {
////			_read.process(map, msg);
////		}
////
////		for (Create _create : create) {
////			_create.process(map, msg);
////		}
//
//		for (Update _update : update) {
//			_update.process(map, msg);
//		}
//
////		for (Delete _delete : delete) {
////			_delete.process(map, msg);
////		}
//
//	}
	
	
	public void process(Map<String, String> map, SipServletMessage msg, Map<String, String> output)
			throws UnsupportedEncodingException, IOException, ServletParseException {

//		for (Read _read : read) {
//			_read.process(map, msg, output);
//		}
//
//		for (Create _create : create) {
//			_create.process(map, msg, output);
//		}

		for (Update _update : update) {
			_update.process(map, msg, output);
		}

//		for (Delete _delete : delete) {
//			_delete.process(map, msg, output);
//		}

	}

}
