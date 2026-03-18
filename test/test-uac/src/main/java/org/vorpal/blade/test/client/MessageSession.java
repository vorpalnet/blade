package org.vorpal.blade.test.client;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;
import javax.ws.rs.container.AsyncResponse;

import org.vorpal.blade.framework.v2.AsyncSipServlet;

public class MessageSession {

	private String id;
	private SipApplicationSession appSession;
	private SipSession sipSession;
	private AsyncResponse asyncResponse;

	public MessageSession() {

	}

	public AsyncResponse getAsyncResponse() {
		return asyncResponse;
	}

	public void setAsyncResponse(AsyncResponse asyncResponse) {
		this.asyncResponse = asyncResponse;
	}

	public MessageSession(SipApplicationSession appSession, SipSession sipSession) {
		this.appSession = appSession;
		this.sipSession = sipSession;

		id = AsyncSipServlet.hash(appSession.getId() + ":" + sipSession.getId());
	}

	public String getId() {
		return id;
	}

	public SipApplicationSession getAppSession() {
		return appSession;
	}

	public void setAppSession(SipApplicationSession appSession) {
		this.appSession = appSession;
	}

	public SipSession getSipSession() {
		return sipSession;
	}

	public void setSipSession(SipSession sipSession) {
		this.sipSession = sipSession;
	}

}
