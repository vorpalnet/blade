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

	/**
	 * Default constructor for JSON deserialization.
	 */
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

	/**
	 * Returns the unique identifier for this tier.
	 *
	 * @return the tier ID
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the unique identifier for this tier.
	 *
	 * @param id the tier ID
	 */
	public void setId(String id) {
		this.id = id;
	}

	/**
	 * Returns the routing mode for this tier.
	 *
	 * @return the routing mode (parallel or serial)
	 */
	public Mode getMode() {
		return mode;
	}

	/**
	 * Sets the routing mode for this tier.
	 *
	 * @param mode the routing mode (parallel or serial)
	 */
	public void setMode(Mode mode) {
		this.mode = mode;
	}

	/**
	 * Returns the timeout in seconds for this tier.
	 *
	 * @return the timeout value
	 */
	public Integer getTimeout() {
		return timeout;
	}

	/**
	 * Sets the timeout in seconds for this tier.
	 *
	 * @param timeout the timeout value
	 */
	public void setTimeout(Integer timeout) {
		this.timeout = timeout;
	}

	/**
	 * Returns the list of endpoint URIs for this tier.
	 *
	 * @return the list of endpoints
	 */
	public List<URI> getEndpoints() {
		return endpoints;
	}

	/**
	 * Sets the list of endpoint URIs for this tier.
	 *
	 * @param endpoints the list of endpoints
	 */
	public void setEndpoints(List<URI> endpoints) {
		this.endpoints = endpoints;
	}

	/**
	 * Adds an endpoint URI to this tier.
	 *
	 * @param endpoint the endpoint URI to add
	 * @return the added endpoint URI
	 */
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
