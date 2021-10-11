package org.vorpal.blade.framework.proxy;

import java.io.Serializable;
import java.util.ArrayList;

import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonPropertyOrder({ "mode", "timeout", "endpoints" })
public class ProxyTier implements Serializable {
	public enum Mode {
		parallel, serial
	}

	@JsonProperty(required = true)
	Mode mode = Mode.serial;

	@JsonProperty(required = false)
	Integer timeout = 0;

	@JsonProperty(required = true)
	ArrayList<URI> endpoints = new ArrayList<>();

	public ProxyTier() {
	}

	public ProxyTier(ProxyTier that) {
		this.mode = that.mode;
		this.timeout = that.timeout;
		this.endpoints = new ArrayList<>(that.getEndpoints());
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

	public ArrayList<URI> getEndpoints() {
		return endpoints;
	}

	public void setEndpoints(ArrayList<URI> endpoints) {
		this.endpoints = endpoints;
	}

}
