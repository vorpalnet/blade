package org.vorpal.blade.services.loadbalancer.proxy;

import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

public class ProxyResponse {
	private URI endpoint;
	private SipServletResponse response;

	public URI getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(URI endpoint) {
		this.endpoint = endpoint;
	}

	public SipServletResponse getResponse() {
		return response;
	}

	public void setResponse(SipServletResponse response) {
		this.response = response;
	}

}
