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
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

/**
 * @author Jeff McDonald
 *
 */
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class AclSipServlet extends SipServlet implements SipServletListener {

	private Logger sipLogger;

	public AclConfigManager configManager;

	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {

		sipLogger.info("request.getServletContext().getServletContextName: "
				+ request.getServletContext().getServletContextName());

		sipLogger.info("request.getServletContext().getContextPath: " + request.getServletContext().getContextPath());

		String attribute;
		Object value;
		Enumeration<String> e;

		e = request.getServletContext().getAttributeNames();
		while (e.hasMoreElements()) {
			attribute = e.nextElement();
			value = request.getServletContext().getAttribute(attribute);
			sipLogger.info("Request attribute " + attribute + "=" + value.toString());
		}

		e = request.getServletContext().getInitParameterNames();
		while (e.hasMoreElements()) {
			attribute = e.nextElement();
			value = request.getServletContext().getInitParameter(attribute);
			sipLogger.info("Request initParameter " + attribute + "=" + value.toString());
		}

		sipLogger.info("request.getSession().getLocalParty: " + request.getSession().getLocalParty());
		sipLogger.info("request.getSession().getRemoteParty: " + request.getSession().getRemoteParty());
		sipLogger.info("request.getSession().getServletContext().getServletContextName: "
				+ request.getSession().getServletContext().getServletContextName());

		sipLogger.info("getInitialPoppedRoute: " + request.getInitialPoppedRoute());
		sipLogger.info("getPoppedRoute: " + request.getPoppedRoute());
		sipLogger.info("getRegion: " + request.getRegion());
		sipLogger.info("getRequestURI: " + request.getRequestURI());
		sipLogger.info("getRoutingDirective: " + request.getRoutingDirective());
		sipLogger.info("getSubscriberURI: " + request.getSubscriberURI());
		sipLogger.info("getAttributeNames: " + request.getAttributeNames());
		sipLogger.info("getLocale: " + request.getLocale());
		sipLogger.info("getLocales: " + request.getLocales());
		sipLogger.info("getLocalName: " + request.getLocalName());
		sipLogger.info("getParameterNames: " + request.getParameterNames());
		sipLogger.info("getRemoteAddr: " + request.getRemoteAddr());
		sipLogger.info("getRemoteHost: " + request.getRemoteHost());
		sipLogger.info("getRemotePort: " + request.getRemotePort());
		sipLogger.info("getServerName: " + request.getServerName());
		sipLogger.info("getServerPort: " + request.getServerPort());
		sipLogger.info("getAcceptLanguage: " + request.getAcceptLanguage());
		sipLogger.info("getAttributeNames: " + request.getAttributeNames());
		sipLogger.info("getFrom: " + request.getFrom());
		sipLogger.info("getHeaderNameList: " + request.getHeaderNameList());
		sipLogger.info("getRemoteUser: " + request.getRemoteUser());
		sipLogger.info("getTo: " + request.getTo());
		sipLogger.info("getUserPrincipal: " + request.getUserPrincipal());

		AclRule.Permission permission = configManager.getCurrent().evaulate(request.getRemoteAddr());
		sipLogger.info("Permission: " + permission);

		if (AclRule.Permission.allow == permission) {
			request.getProxy().proxyTo(request.getRequestURI());
		} else {
			request.createResponse(403).send();
		}

	}

	@Override
	public void servletInitialized(SipServletContextEvent event) {

		sipLogger = LogManager.getLogger(event.getServletContext());
		Callflow.setLogger(sipLogger);

		String name = event.getServletContext().getServletContextName();
		configManager = new AclConfigManager(name);
		sipLogger.logConfiguration(configManager.getCurrent());

	}

}
