package org.vorpal.blade.services.proxy.balancer;

import javax.servlet.sip.ServletParseException;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyServlet;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;
import org.vorpal.blade.framework.v2.proxy.ProxyTier.Mode;

public class ProxyBalancerConfigSample extends ProxyBalancerConfig {

	private static final long serialVersionUID = 1L;

	public ProxyBalancerConfigSample() throws ServletParseException {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();

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
