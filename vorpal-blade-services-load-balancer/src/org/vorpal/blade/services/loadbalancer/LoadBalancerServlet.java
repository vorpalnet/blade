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
package org.vorpal.blade.services.loadbalancer;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.services.loadbalancer.config.ProxyConfig;
import org.vorpal.blade.services.loadbalancer.config.ProxyConfigSample;
import org.vorpal.blade.services.loadbalancer.proxy.ProxyRule;
import org.vorpal.blade.services.loadbalancer.proxy.ProxyServlet;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class LoadBalancerServlet extends ProxyServlet {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<ProxyConfigSample> settingsManager;

	public Logger sipLogger;

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		sipLogger = LogManager.getLogger(event);
		sipLogger.info("Starting Load-Balancer...");
		settingsManager = new SettingsManager<>(event, ProxyConfigSample.class);
		sipLogger.logConfiguration(settingsManager.getCurrent());

	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		sipLogger.info("Stopping Load-Balancer...");
	}

	@Override
	public ProxyRule proxyRequest(SipServletRequest request) {

		return null;
	}

	@Override
	public void proxyResponse(SipServletResponse outboundResponse, List<SipServletResponse> proxyResponses)
			throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
