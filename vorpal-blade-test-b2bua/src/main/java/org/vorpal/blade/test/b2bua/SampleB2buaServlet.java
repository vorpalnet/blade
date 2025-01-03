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
package org.vorpal.blade.test.b2bua;

import java.io.IOException;
import java.util.logging.Level;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Color;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class SampleB2buaServlet extends B2buaServlet implements SipApplicationSessionListener, SipSessionListener {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<SampleB2buaConfig> settingsManager;

	@Resource
	private SipFactory sipFactory;

	/*
	 * This is invoked when the servlet starts up.
	 */

	@Override
	public void servletCreated(SipServletContextEvent event) {

		try {
			// Normally, the SettingsManager will set the sipFactory, but in this example,
			// we need it already set to create some SIP Address and URI values in the
			// sample config file.
			this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");

			settingsManager = new SettingsManager<SampleB2buaConfig>(event, SampleB2buaConfig.class,
					new ConfigSample());
			sipLogger.info("servletCreated...");

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	/*
	 * This is invoked when the servlet shuts down.
	 */
	@Override
	public void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("servletDestroyed...");
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
		sipLogger.info(request, "callStarted...");

	}

	/*
	 * This is the final response to Alice, it can be modified.
	 */
	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
		sipLogger.info(response, "callAnswered...");

	}

	/*
	 * This is the ACK sent to Alice.
	 */
	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {
		sipLogger.info(request, "callConnected...");
	}

	/*
	 * This should be a BYE request from either Alice or Bob.
	 */
	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
		sipLogger.info(request, "callCompleted...");
	}

	/*
	 * This should be the error code from Bob, the destination.
	 */
	@Override
	public void callDeclined(SipServletResponse response) throws ServletException, IOException {
		sipLogger.info(response, "callDeclined...");
	}

	/*
	 * This should be a CANCEL from Alice.
	 */
	@Override
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException {
		sipLogger.info(request, "callAbandoned...");
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {

		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, Color.GREEN_BRIGHT("appSession created... " + appSession.getId()));
		}

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, Color.RED_BRIGHT("appSession destroyed... " + appSession.getId()));
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession expired... " + appSession.getId());
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession readyToInvalidate... " + appSession.getId());
		}
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession created... " + sipSession.getId());
		}
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession destroyed... " + sipSession.getId());

		}
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession readyToInvalidate... " + sipSession.getId());
		}
	}

}
