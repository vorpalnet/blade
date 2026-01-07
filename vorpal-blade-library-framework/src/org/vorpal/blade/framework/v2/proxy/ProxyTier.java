package org.vorpal.blade.framework.v2.proxy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.URI;

/**
 * Represents a routing tier within a proxy plan.
 * A tier contains endpoints that can be tried in parallel or serial mode with an optional timeout.
 */
public class ProxyTier implements Serializable {
	private static final long serialVersionUID = 1L;

	/**
	 * Routing mode for endpoints within this tier.
	 */
	public enum Mode {
		/** Try all endpoints simultaneously */
		parallel,
		/** Try endpoints one at a time in order */
		serial
	}

	/** Default timeout value in seconds */
	private static final Integer DEFAULT_TIMEOUT = 0;

	private String id = null;
	private Mode mode = Mode.serial;
	private Integer timeout = DEFAULT_TIMEOUT;
	private List<URI> endpoints = new ArrayList<>();

	public ProxyTier() {
	}

	/**
	 * Copy constructor.
	 *
	 * @param that the ProxyTier to copy
	 */
	public ProxyTier(ProxyTier that) {
		if (that != null) {
			this.mode = that.mode;
			this.timeout = that.timeout;
			this.endpoints = that.endpoints != null ? new ArrayList<>(that.endpoints) : new ArrayList<>();
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public Integer getTimeout() {
		return timeout;
	}

	public void setTimeout(Integer timeout) {
		this.timeout = timeout;
	}

	public List<URI> getEndpoints() {
		return endpoints;
	}

	public void setEndpoints(List<URI> endpoints) {
		this.endpoints = endpoints;
	}

	public URI addEndpoint(URI endpoint) {
		if (endpoint != null) {
			if (this.endpoints == null) {
				this.endpoints = new ArrayList<>();
			}
			this.endpoints.add(endpoint);
		}
		return endpoint;
	}

}
