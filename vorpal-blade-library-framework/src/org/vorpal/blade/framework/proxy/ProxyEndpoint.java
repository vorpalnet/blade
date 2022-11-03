package org.vorpal.blade.framework.proxy;

import javax.servlet.sip.URI;

public class ProxyEndpoint {
	private URI uri;
	private Boolean active = true;

	public URI getUri() {
		return uri;
	}

	public void setUri(URI uri) {
		this.uri = uri;
	}

	public Boolean getActive() {
		return active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}

	public ProxyEndpoint() {

	}

	public ProxyEndpoint(URI uri) {
		this.uri = uri;
	}

}
