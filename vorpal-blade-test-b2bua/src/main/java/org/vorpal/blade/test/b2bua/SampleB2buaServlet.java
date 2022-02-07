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

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.test.config.SampleConfig;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class SampleB2buaServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;
	private static SettingsManager<SampleConfig> settingsManager;

	/*
	 * This is invoked when the servlet starts up.
	 */
	@Override
	public void servletCreated(SipServletContextEvent event) {
		try {

			sipLogger.info("b2buaCreated...");
			settingsManager = new SettingsManager<>(event, SampleConfig.class);

			SampleConfig sampleConfig = settingsManager.getCurrent();

			sipLogger.info("This is the current config:");
			sipLogger.logConfiguration(sampleConfig);

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
			sipLogger.info("b2buaDestroyed...");
			settingsManager.unregister();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	/*
	 * This is the outbound INVITE request to Bob, it can be modified.
	 */
	@Override
	public void callStarted(SipServletRequest request) {
		
		sipLogger.info(request, "callStarted...");
		
//		request.getApplicationSession().setExpires(86400); // 24 hours
//		String bobValue = SampleB2buaServlet.sampleConfiguration.getBobRequestData();
//		request.setHeader("X-Sample-B2bua-Request", bobValue);
		
	}

	/*
	 * This is the final response to Alice, it can be modified.
	 */
	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
		sipLogger.info(response, "callAnswered...");

		//		String aliceValue = SampleB2buaServlet.sampleConfiguration.getAliceResponseData();
//		response.setHeader("X-Sample-B2bua-Response", aliceValue);
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

}
