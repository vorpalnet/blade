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
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.AsyncSipServlet;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class TestB2buaSipServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;
	public static TestB2buaSettingsManager settingsManager;

	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		Callflow callflow = null;

		switch (inboundRequest.getMethod()) {
		case Callflow.INVITE:
			callflow = new TestB2buaInvite();
			break;
		case Callflow.CANCEL:
			callflow = new TestB2buaCancel();
			break;
		default:
			callflow = new TestB2buaPassthru();
		}

		return callflow;
	}

	/*
	 * This is invoked when the servlet starts up.
	 */
	@Override
	public void servletCreated(SipServletContextEvent event) {
		try {
			settingsManager = new TestB2buaSettingsManager(event);

			Integer version = settingsManager.getCurrent().getVersion();

			sipLogger.info(settingsManager.getServletContextName() + " loaded configuration version " + version);

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
			settingsManager.unregister();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

}
