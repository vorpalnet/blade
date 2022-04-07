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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.proxy.ProxyRule;
import org.vorpal.blade.framework.proxy.ProxyServlet;
import org.vorpal.blade.framework.proxy.ProxyTier;
import org.vorpal.blade.services.loadbalancer.config.AccessControl;
import org.vorpal.blade.services.loadbalancer.config.AclConfig;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

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
	public static LoadBalancerSettingsManager settingsManager;
	public static AclConfig aclConfig;

	public IPAddress getRemoteAddress(SipApplicationSession appSession) {
		String remoteAddress = null;

		SipSession sipSession;
		SipServletRequest activeInvite = null;
		Iterator<SipSession> itr = (Iterator<SipSession>) appSession.getSessions();
		while (itr.hasNext()) {
			sipSession = itr.next();

			activeInvite = sipSession.getActiveInvite(UAMode.UAS);
			if (activeInvite != null) {
				remoteAddress = activeInvite.getRemoteAddr();
				break;
			}
		}

		sipLogger.fine("remoteAddress: " + remoteAddress);

		return new IPAddressString(remoteAddress).getAddress();
	}

	@Override
	public ProxyTier proxyRequest(SipServletRequest request) {
		ProxyTier proxyTier = null;
		List<ProxyTier> proxyTiers;
		SipApplicationSession appSession = request.getApplicationSession();
		proxyTiers = (List<ProxyTier>) appSession.getAttribute("PROXY_TIERS");

		if (proxyTiers == null) {

			IPAddress remoteAddress = getRemoteAddress(request.getApplicationSession());
			AccessControl accessControl = aclConfig.accessControlList.get(remoteAddress);

			if (accessControl == null) {
				accessControl = new AccessControl();
				AccessControl.Permission defaultPermission = settingsManager.getCurrent().getDefaultPermission();
				accessControl.setPermission(defaultPermission);
			}

			String proxyRuleId = accessControl.getProxyRuleId();
			sipLogger.fine("proxyRuleId: " + proxyRuleId);
			if (proxyRuleId != null && proxyRuleId.length() > 0) {
				ProxyRule proxyRule = aclConfig.proxyRules.get(proxyRuleId);

				// make a deep copy so we can edit it in the future
				proxyTiers = new LinkedList<>(proxyRule.getTiers());

			}
		}

		if (proxyTiers != null && proxyTiers.size() > 0) {
			proxyTier = proxyTiers.remove(0);
			appSession.setAttribute("PROXY_TIERS", proxyTiers);
		}

		if (proxyTier != null) {
			sipLogger.info("proxyTier mode: " + proxyTier.getMode() + ", timeout: " + proxyTier.getTimeout()
					+ ", endpoints: " + proxyTier.getEndpoints());
		}
		return proxyTier;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new LoadBalancerSettingsManager(event);
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
	}

	@Override
	public void proxyResponse(SipServletResponse response) throws ServletException, IOException {
	}

}
