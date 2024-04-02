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
import java.util.Hashtable;
import java.util.Set;

import javax.annotation.Resource;
import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;

import org.vorpal.blade.applications.console.config.test.BladeConsoleMXBean;
import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.config.SettingsMXBean;
import org.vorpal.blade.framework.config.SettingsManager;

import weblogic.jndi.Environment;

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
		sipLogger.finer(event.getApplicationSession(), "sessionCreated.");
	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(),
				"sessionDestroyed, sessionCount=" + event.getApplicationSession().getSessionSet().size());

	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(),
				"sessionExpired, sessionCount=" + event.getApplicationSession().getSessionSet().size());
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(),
				"sessionReadyToInvalidate, sessionCount=" + event.getApplicationSession().getSessionSet().size());
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		sipLogger.finer(event.getSession(),
				"sessionCreated, sessionCount=" + event.getSession().getApplicationSession().getSessionSet().size());
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		sipLogger.finer(event.getSession(),
				"sessionDestroyed, sessionCount=" + event.getSession().getApplicationSession().getSessionSet().size());
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		sipLogger.finer(event.getSession(), "sessionReadyToInvalidate, sessionCount="
				+ event.getSession().getApplicationSession().getSessionSet().size());
	}

}
