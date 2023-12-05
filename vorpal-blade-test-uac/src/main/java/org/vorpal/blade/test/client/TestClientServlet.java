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
package org.vorpal.blade.test.client;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.tpcc.TestReinvite;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class TestClientServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;
	private static SettingsManager<TestClientConfig> settingsManager;

	/*
	 * This is invoked when the servlet starts up.
	 */
	@Override
	public void servletCreated(SipServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<TestClientConfig>(event, TestClientConfig.class,
					new TestClientConfigDefault());
			this.sipLogger = SettingsManager.getSipLogger();

//			System.out.println("TestClientServlet... sipLogger.getLevel(): " + sipLogger.getLevel());			
//			System.out.println("TestClientServlet... sipLogger.getParent().getLevel(): " + sipLogger.getParent().getLevel());			
			
			
//			sipLogger.log(sipLogger.getLevel(), "b2buaCreated...");
//			sipLogger.log(sipLogger.getLevel(), "Logging level set to: " + sipLogger.getLevel());
			sipLogger.info("b2buaCreated...");
//			sipLogger.info("Logging level set to: " + sipLogger.getLevel());

		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.logStackTrace(e);
			} else {
				e.printStackTrace();
			}
		}

	}

	/*
	 * This is invoked when the servlet shuts down.
	 */
	@Override
	public void servletDestroyed(SipServletContextEvent event) {

		try {
			if (sipLogger != null) {

//				sipLogger.log(sipLogger.getLevel(), "b2buaDestroyed...");
				sipLogger.severe("b2buaDestroyed...");

			} else {
				System.out.println("TestClientServlet.b2buaDestroyed... sipLogger not defined!");
			}

			settingsManager.unregister();

		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.logStackTrace(e);
			} else {
				e.printStackTrace();
			}
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		switch (request.getMethod()) {
		case "INVITE":
			callflow = new TestReinvite();
			break;
		case "BYE":
			callflow = new TestClientBye();
			break;
		}

		return callflow;
	}

}
