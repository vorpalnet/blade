package org.vorpal.blade.services.options;

import java.io.Serializable;

public class OptionsSettings implements Serializable{

	String accept = "application/sdp";
	String acceptLanguage = "en";
	String allow = "INVITE, ACK, BYE, CANCEL, REGISTER, OPTIONS, PRACK, SUBSCRIBE, NOTIFY, PUBLISH, INFO, REFER, MESSAGE, UPDATE";
	String supported = "replaces";
	String userAgent = "OCCAS 7.1";
	String allowEvents = "talk, hold";

	
	
	
	
	
	public String getAllow() {
		return allow;
	}

	public void setAllow(String allow) {
		this.allow = allow;
	}

	public String getAccept() {
		return accept;
	}

	public void setAccept(String accept) {
		this.accept = accept;
	}

	public String getAcceptLanguage() {
		return acceptLanguage;
	}

	public void setAcceptLanguage(String acceptLanguage) {
		this.acceptLanguage = acceptLanguage;
	}

	public String getSupported() {
		return supported;
	}

	public void setSupported(String supported) {
		this.supported = supported;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public String getAllowEvents() {
		return allowEvents;
	}

	public void setAllowEvents(String allowEvents) {
		this.allowEvents = allowEvents;
	}

}
