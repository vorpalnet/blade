package org.vorpal.blade.services.loadbalancer.config;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.loadbalancer.proxy.ProxyRule;
import org.vorpal.blade.services.loadbalancer.proxy.ProxyTier;


public class ProxyConfigSample extends ProxyConfig{
	
	public ProxyConfigSample() throws ServletParseException {
		
		this.setStateless(true);

		SipFactory sipFactory = SettingsManager.getSipFactory();

		ProxyRule rule1 = new ProxyRule();

		ProxyTier proxyTier1 = new ProxyTier();
		proxyTier1.setMode(ProxyTier.Mode.serial);
		proxyTier1.setTimeout(5);
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:bob@vorpal.net;status=403"));
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:bob@vorpal.net;status=404"));
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:bob@vorpal.net"));
		rule1.getTiers().add(proxyTier1);

		ProxyTier proxyTier2 = new ProxyTier();
		proxyTier2.setMode(ProxyTier.Mode.parallel);
		proxyTier2.setTimeout(5);
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:bob@vorpal.net;status=410"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:bob@vorpal.net;status=503"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:bob@vorpal.net"));
		rule1.getTiers().add(proxyTier2);

		this.getRules().put("bob", rule1);

	}

}
