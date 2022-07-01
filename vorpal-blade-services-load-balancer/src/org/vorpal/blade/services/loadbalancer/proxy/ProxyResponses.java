package org.vorpal.blade.services.loadbalancer.proxy;

import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

public class ProxyResponses {

	private SipServletRequest request = null;
	private ProxyRule previous = null;
	private ProxyRule next = null;
	private List<ProxyResponse> responses = new LinkedList<>();

	public ProxyResponses() {
	}

	public ProxyResponses(SipServletRequest request) {
		this.request = request;
	}

	public List<ProxyResponse> getResponses() {
		return responses;
	}

	public void setResponses(List<ProxyResponse> responses) {
		this.responses = responses;
	}

	public ProxyRule getPrevious() {
		return previous;
	}

	public void setNext(ProxyRule next) {
		this.next = next;
	}

}
