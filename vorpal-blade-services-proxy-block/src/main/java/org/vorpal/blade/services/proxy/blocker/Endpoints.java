package org.vorpal.blade.services.proxy.blocker;

import java.util.LinkedList;
import java.util.List;

public class Endpoints {
	private List<String> requestURIs = new LinkedList<>();

	public Endpoints() {
	}

	public Endpoints(String... strings) {
		for (String str : strings) {
			this.requestURIs.add(str);
		}
	}

	public List<String> getRequestURIs() {
		return requestURIs;
	}

	public Endpoints setRequestURIs(List<String> requestURIs) {
		this.requestURIs = requestURIs;
		return this;
	}

	public Endpoints addEndpoint(String endpoint) {
		this.requestURIs.add(endpoint);
		return this;
	}

}
