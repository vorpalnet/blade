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
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
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
//public class SampleB2buaServlet extends B2buaServlet implements SipApplicationSessionListener, SipSessionListener {
public class SampleB2buaServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<SampleB2buaConfig> settingsManager;

	@Resource
	private SipFactory sipFactory;

	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		Callflow callflow = null;

		// choose your custom callflows here

		callflow = super.chooseCallflow(inboundRequest);

		return callflow;
	}

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

			sipLogger.fine(
					"Stop. Who would cross the Bridge of Death must answer me these questions three, ere the other side he see.");
			sipLogger.info("What is your name? " + settingsManager.getCurrent().traveler);
			sipLogger.info("What is your quest? " + settingsManager.getCurrent().quest);
			sipLogger
					.info("What is your favorite color? " + Color.BLUE_BOLD_BRIGHT(settingsManager.getCurrent().quest));

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

		// for testing...
//		sipLogger.warning(request, "SampleB2buaServlet.callStarted - About to produce a null pointer exception...");
//		Callflow cf = null;
//		cf.process(null);
	}

	/*
	 * This is the final response to Alice, it can be modified.
	 */
	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
		sipLogger.info(response, "callAnswered...");

//		// for testing...
//		sipLogger.warning(response, "SampleB2buaServlet.callAnswered - About to produce a null pointer exception...");
//		Callflow cf = null;
//		cf.process(null);

	}

	/*
	 * This is the ACK sent to Alice.
	 */
	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {
		sipLogger.info(request, "callConnected...");

// for testing...
//		sipLogger.warning(request, "SampleB2buaServlet.callStarted - About to produce a null pointer exception...");
//		Callflow cf = null;
//		cf.process(null);

	}

	/*
	 * This should be a BYE request from either Alice or Bob.
	 */
	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
		sipLogger.info(request, "callCompleted...");

		// for testing...
//		sipLogger.warning(request, "SampleB2buaServlet.callStarted - About to produce a null pointer exception...");
//		Callflow cf = null;
//		cf.process(null);

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

//	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {

		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, Color.GREEN_BRIGHT("appSession created... " + appSession.getId()));
		}

	}

//	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, Color.RED_BRIGHT("appSession destroyed... " + appSession.getId()));
		}
	}

//	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession expired... " + appSession.getId());
		}
	}

//	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession readyToInvalidate... " + appSession.getId());
		}
	}

//	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession created... " + sipSession.getId());
		}
	}

//	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession destroyed... " + sipSession.getId());

		}
	}

//	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession readyToInvalidate... " + sipSession.getId());
		}
	}

	/**
	 * Called for mid-dialog requests (e.g., re-INVITE, INFO, UPDATE).
	 *
	 * @param bobRequest the outbound request to modify
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException      if an I/O error occurs
	 */
	@Override
	public void requestEvent(SipServletRequest bobRequest) throws ServletException, IOException {

	}

	/**
	 * Called for mid-dialog responses.
	 *
	 * @param aliceResponse the response to modify before sending
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException      if an I/O error occurs
	 */
	@Override
	public void responseEvent(SipServletResponse aliceResponse) throws ServletException, IOException {

// jwm - testing to see what happense if Bob disappears
//		if (aliceResponse.getMethod().equals(Callflow.NOTIFY)) {
//			sipLogger.finer(aliceResponse, "SampleB2buaServlet.responseEvent - ");
//			aliceResponse.setStatus(404);
//		}

	}

}
