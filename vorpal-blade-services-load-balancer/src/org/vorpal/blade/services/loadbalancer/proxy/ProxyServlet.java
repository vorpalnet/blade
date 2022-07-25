package org.vorpal.blade.services.loadbalancer.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.b2bua.Bye;
import org.vorpal.blade.framework.b2bua.Cancel;
import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.framework.b2bua.Passthru;
import org.vorpal.blade.framework.b2bua.Reinvite;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.loadbalancer.LoadBalancerServlet;

public abstract class ProxyServlet extends AsyncSipServlet implements ProxyListener {

	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		Callflow callflow;

		if (inboundRequest.getMethod().equals("INVITE")) {
			if (inboundRequest.isInitial()) {

				String key = ((SipURI) inboundRequest.getRequestURI()).getHost();

				SettingsManager.getSipLogger().fine(inboundRequest, "Looking up key: " + key);

				ProxyRule rule = LoadBalancerServlet.settingsManager.getCurrent().getRules().get(key);

				if (rule != null) {
					SettingsManager.getSipLogger().fine(inboundRequest,
							"Rule found... id: " + rule.getId() + ", tiers: " + rule.getTiers().size());
					callflow = new ProxyInvite(this, rule);
				} else {
					SettingsManager.getSipLogger().fine(inboundRequest, "No rule found for key: " + key);
					callflow = new InitialInvite();

				}

			} else {
				callflow = new Reinvite();
			}
		} else if (inboundRequest.getMethod().equals("BYE")) {
			callflow = new Bye();
		} else if (inboundRequest.getMethod().equals("CANCEL")) {
			callflow = new Cancel();
		} else {
			callflow = new Passthru();
		}

		return callflow;
	}

}
