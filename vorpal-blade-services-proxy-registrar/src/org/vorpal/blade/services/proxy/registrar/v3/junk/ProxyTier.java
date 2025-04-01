package org.vorpal.blade.services.proxy.registrar.v3.junk;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

public class ProxyTier implements Serializable {

	public String id = null;
	public boolean parallel = true;
	public Integer timeout = 0;
	public List<URI> endpoints = new ArrayList<>();
	public SipServletResponse finalResponse;

	public ProxyTier() {
	}

	public ProxyTier(boolean parallel, int seconds) {
		this.parallel = parallel;
		this.timeout = seconds;
	}

	public URI addEndpoint(URI endpoint) {
		endpoints.add(endpoint);
		return endpoint;
	}

}
