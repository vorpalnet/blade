package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;
import java.util.ArrayList;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

import org.vorpal.blade.services.proxy.balancer.config.Tier.Strategy;

public class BalancerConfigSample extends BalancerConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	public BalancerConfigSample() {
		// v3 config shape — see BalancerConfig.getVersion().
		this.setVersion(3);
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);
		this.session = new SessionParametersDefault();
		// drop out of the dialog after call setup — the balancer is a
		// routing-only touch point; set false to stay in the path as a B2BUA
		this.session.setPassthru(true);

		this.getHealth().setPingEnabled(true).setPingInterval(60).setDefaultBackoff(60);

		// sites give the map view its geography (fictional coordinates); a
		// site without lat/lon ("lab") lands in the map's unplaced tray
		this.getSites().put("dc-east", new Site("Eastern DC", 39.0, -77.5));
		this.getSites().put("dc-west", new Site("Western DC", 37.4, -122.0));
		this.getSites().put("lab", new Site("Lab"));
		this.setClusterSite("dc-east");

		// endpoint registry: defined once, referenced by name from any tier.
		// (blade1..blade5 are the test-UAS hosts; the user part and the
		// status=/delay= uriParams drive TesterServlet's scripted responses —
		// see Endpoint.getUriParams().)
		this.getEndpoints().put("blade1", new Endpoint("blade1").setUser("transferor").setUriParams("status=501;delay=2")
				.setWeight(3).setSite("dc-east"));
		this.getEndpoints().put("blade2", new Endpoint("blade2").setUser("transferor").setUriParams("status=502;delay=4")
				.setSite("dc-east"));
		this.getEndpoints().put("blade3", new Endpoint("blade3").setUser("transferor").setUriParams("status=503;delay=2")
				.setSite("dc-west"));
		this.getEndpoints().put("blade4", new Endpoint("blade4").setUser("transferor").setUriParams("status=504;delay=2")
				.setSite("dc-west"));
		this.getEndpoints().put("blade5", new Endpoint("blade5").setUser("transferor").setSite("lab"));

		// weighted equal-cost pool first (blade1 gets ~3x the first attempts),
		// strict-priority hunt as the fallback band
		ArrayList<Tier> plan = new ArrayList<>();
		plan.add(new Tier().setName("primary").setStrategy(Strategy.weighted).setTimeout(15)
				.addEndpoint("blade1").addEndpoint("blade2"));
		plan.add(new Tier().setName("fallback").setStrategy(Strategy.serial).setTimeout(15)
				.addEndpoint("blade3").addEndpoint("blade4").addEndpoint("blade5"));

		this.getPlans().put("test1", plan);

	}

}
