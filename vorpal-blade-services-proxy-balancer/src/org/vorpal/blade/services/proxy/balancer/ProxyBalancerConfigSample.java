package org.vorpal.blade.services.proxy.balancer;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.proxy.ProxyPlan;
import org.vorpal.blade.framework.proxy.ProxyServlet;
import org.vorpal.blade.framework.proxy.ProxyTier;
import org.vorpal.blade.framework.proxy.ProxyTier.Mode;

public class ProxyBalancerConfigSample extends ProxyBalancerConfig {

	public ProxyBalancerConfigSample() throws ServletParseException {
		SipFactory sipFactory = SettingsManager.getSipFactory();

		ProxyPlan plan1 = new ProxyPlan();

		ProxyTier proxyTier1 = new ProxyTier();
		proxyTier1.setMode(Mode.parallel);
		proxyTier1.setTimeout(15);

		proxyTier1.getEndpoints()
				.add(ProxyServlet.getSipFactory().createURI("sip:transferor@blade1;status=501;delay=2"));
		proxyTier1.getEndpoints()
				.add(ProxyServlet.getSipFactory().createURI("sip:transferor@blade2;status=502;delay=4"));
		plan1.getTiers().add(proxyTier1);

		ProxyTier proxyTier2 = new ProxyTier();
		proxyTier2.setMode(Mode.serial);
		proxyTier2.setTimeout(15);
		proxyTier2.getEndpoints()
				.add(ProxyServlet.getSipFactory().createURI("sip:transferor@blade3;status=503;delay=2"));
		proxyTier2.getEndpoints()
				.add(ProxyServlet.getSipFactory().createURI("sip:transferor@blade4;status=504;delay=2"));
		proxyTier2.getEndpoints().add(ProxyServlet.getSipFactory().createURI("sip:transferor@blade5"));
		plan1.getTiers().add(proxyTier2);

		this.getPlans().put("test1", plan1);

	}

}
