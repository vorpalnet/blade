package com.optum.omnichannel.proxy;

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

import org.vorpal.blade.framework.proxy.ProxyServlet;
import org.vorpal.blade.framework.proxy.ProxyTier;

import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;

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

	public String getRemoteAddress(SipApplicationSession appSession) {
//		IPv4Address ipv4Address;
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

//		ipv4Address = new IPAddressString(remoteAddress).getAddress().toIPv4();
//		sipLogger.fine("remoteAddress: " + ipv4Address);
		sipLogger.fine("remoteAddress: " + remoteAddress);

		return remoteAddress;
	}

	@Override
	public ProxyTier proxyRequest(SipServletRequest request) {

		ProxyTier proxyTier = null;
		List<ProxyTier> proxyTiers;
		SipApplicationSession appSession = request.getApplicationSession();
		proxyTiers = (List<ProxyTier>) appSession.getAttribute("PROXY_TIERS");

		if (proxyTiers == null) {

			String remoteAddress = getRemoteAddress(request.getApplicationSession());

			// jwm-trie
			IPv4Address addr = new IPAddressString(remoteAddress).getAddress().toIPv4();
			AccessControl accessControl = aclConfig.accessControlList.get(addr);

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
