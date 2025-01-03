/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.services.keepalive;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SessionKeepAlive.Preference;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class KeepAliveServlet extends B2buaServlet implements SipApplicationSessionListener {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<KeepAliveConfig> settingsManager;
	private static final String ALLOWS_UPDATE = "ALLOWS_UPDATE";
	private static final String Allow = "Allow";
	private static final Object UPDATE = "UPDATE";

	/*
	 * This is invoked when the servlet starts up.
	 */
	@Override
	public void servletCreated(SipServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<>(event, KeepAliveConfig.class);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	/*
	 * This is invoked when the servlet shuts down.
	 */
	@Override
	public void servletDestroyed(SipServletContextEvent event) {
		try {
			settingsManager.unregister();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	/*
	 * This is the outbound INVITE request to Bob, it can be modified.
	 */
	@Override
	public void callStarted(SipServletRequest request) throws ServletException, IOException {

		for (String value : request.getHeaderList(Allow)) {
			if (value.equals(UPDATE)) {
				request.getApplicationSession().setAttribute(ALLOWS_UPDATE, true);
				break;
			}
		}

		KeepAliveConfig config = settingsManager.getCurrent();
		Preference preference = request.getSessionKeepAlivePreference();

		if (preference.getEnabled() == false) {
			preference.setEnabled(true);
			preference.setExpiration(config.getSessionExpires());
			preference.setMinimumExpiration(config.getMinSE());
		}

	}

	/*
	 * This is the final response to Alice, it can be modified.
	 */
	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {

		SipApplicationSession appSession = response.getApplicationSession();

		boolean bobAllowsUpdate = false;
		boolean aliceAllowsUpdate = (null != appSession.getAttribute(ALLOWS_UPDATE)) ? true : false;
		if (aliceAllowsUpdate) {
			for (String value : response.getHeaderList(Allow)) {
				if (value.equals(UPDATE)) {
					bobAllowsUpdate = true;
					break;
				}
			}
		}
		appSession.removeAttribute(ALLOWS_UPDATE);

		SessionKeepAlive keepAlive = Callflow.getLinkedSession(response.getSession()).getKeepAlive();
		keepAlive.setExpiryCallback(new KeepAliveExpiry());
		if (aliceAllowsUpdate && bobAllowsUpdate) {
			keepAlive.setRefreshCallback(new KeepAliveUpdate());
		} else {
			keepAlive.setRefreshCallback(new KeepAliveReinvite());
		}

	}

	/*
	 * This is the ACK sent to Alice.
	 */
	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {

	}

	/*
	 * This should be a BYE request from either Alice or Bob.
	 */
	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
	}

	/*
	 * This should be the error code from Bob, the destination.
	 */
	@Override
	public void callDeclined(SipServletResponse response) throws ServletException, IOException {

	}

	/*
	 * This should be a CANCEL from Alice.
	 */
	@Override
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException {
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "sessionCreated");

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "sessionDestroyed");

	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "sessionExpired");

	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "sessionReadyToInvalidate");

	}

}
