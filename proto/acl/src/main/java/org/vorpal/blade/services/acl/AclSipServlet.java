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
package org.vorpal.blade.services.acl;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/**
 * @author Jeff McDonald
 *
 */
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class AclSipServlet extends SipServlet implements SipServletListener {

	private static final long serialVersionUID = 1L;

	private Logger sipLogger;

	public AclConfigManager configManager;

	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {

		AclRule.Permission permission = configManager.getCurrent().evaulate(request.getRemoteAddr());
		if (sipLogger.isLoggable(java.util.logging.Level.FINE)) {
			sipLogger.fine(request, "AclSipServlet - " + request.getMethod() + " from " + request.getRemoteAddr()
					+ ": " + permission);
		}

		if (AclRule.Permission.allow == permission) {
			request.getProxy().proxyTo(request.getRequestURI());
		} else {
			request.createResponse(403).send();
			Events.publish(BladeEventTypes.SIP_DENIED, request.getRemoteAddr(), data -> data
					.put("method", request.getMethod())
					.put("sourceAddress", request.getRemoteAddr())
					.put("from", String.valueOf(request.getFrom()))
					.put("requestUri", String.valueOf(request.getRequestURI()))
					.put("node", SettingsManager.getServerName()));
		}

	}

	@Override
	public void servletInitialized(SipServletContextEvent event) {

		try {

			sipLogger = LogManager.getLogger(event.getServletContext());
			Callflow.setLogger(sipLogger);

			configManager = new AclConfigManager(event);
			sipLogger.logConfiguration(configManager.getCurrent());

		} catch (Exception e) {
			e.printStackTrace();
			sipLogger.severe(e);
		}

	}

}
