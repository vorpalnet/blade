package org.vorpal.blade.framework.proxy;

import java.io.Serializable;
import java.util.ArrayList;

import javax.servlet.sip.URI;

public class ProxyTier implements Serializable {
	public enum Mode {
		parallel, serial
	}

	private String id = null;
	private Mode mode = Mode.serial;
	private Integer timeout = 0;
	private ArrayList<ProxyEndpoint> endpoints = new ArrayList<>();

	public ProxyTier() {
	}

	public ProxyTier(URI endpoint) {
		this.endpoints.add(new ProxyEndpoint(endpoint));
	}

	public ProxyTier(ProxyTier that) {
		this.mode = that.mode;
		this.timeout = that.timeout;
		this.endpoints = new ArrayList<ProxyEndpoint>(that.endpoints);
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

	public ArrayList<ProxyEndpoint> getEndpoints() {
		return endpoints;
	}

	public void setEndpoints(ArrayList<ProxyEndpoint> endpoints) {
		this.endpoints = endpoints;
	}

	public void addEndpoint(ProxyEndpoint endpoint) {
		this.endpoints.add(endpoint);
	}

}
