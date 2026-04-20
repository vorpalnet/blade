package org.vorpal.blade.services.irouter;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.TableConnector;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.routing.TableRouting;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;

/// Sample iRouter configuration written to `_samples/` on first deploy.
///
/// Demonstrates the two-phase v3 model:
///
/// 1. **Pipeline (enrichment)** — a [SipConnector] pulls the remote IP
///    and destination number out of the SIP request, then a
///    [TableConnector] (hash / exact-match) looks up per-customer
///    credentials keyed on the remote IP. Each match spreads its fields
///    (`customerId`, `apiKey`, …) into the session [Context] so every
///    downstream stage (and the routing decision) can interpolate them
///    with `${var}`.
/// 2. **Routing (decision)** — a [TableRouting] uses longest-prefix
///    match on the destination number (telco dial-plan style) to pick
///    the carrier, with a default fallback for unknown prefixes. Each
///    matched [Route] stamps `X-Customer-Id` from the enriched context.
public class IRouterConfigSample extends IRouterConfig {
	private static final long serialVersionUID = 1L;

	public IRouterConfigSample() {

		// ----- Pipeline step 1: SIP parse -----
		SipConnector sip = new SipConnector();
		sip.setId("sip");
		sip.setDescription("Extract caller's remote IP and destination number");
		sip.addSelector(new RegexSelector("remoteIP", "remoteIP", ".*", "${0}"));
		sip.addSelector(new RegexSelector("destNum", "To", ".*<sips?:\\+?(?<did>\\d+).*", "${did}"));

		// ----- Pipeline step 2: customer enrichment (hash / exact-match) -----
		TableConnector customers = new TableConnector();
		customers.setId("customers");
		customers.setDescription("Enrichment: customer credentials keyed by remote IP (exact match)");
		customers.setMatch(MatchStrategy.hash);
		customers.setKeyExpression("${remoteIP}");
		customers.createTranslation("172.16.32.173")
				.put("customerId", "acme")
				.put("apiKey", "acme-api-key-redacted");
		customers.createTranslation("10.4.5.6")
				.put("customerId", "globex")
				.put("apiKey", "globex-api-key-redacted");

		// ----- Assemble the pipeline -----
		this.setPipeline(new LinkedList<>());
		this.getPipeline().add(sip);
		this.getPipeline().add(customers);

		// ----- Routing decision: longest-prefix dial-plan -----
		TableRouting routing = new TableRouting();
		routing.setMatch(MatchStrategy.prefix);
		routing.setKeyExpression("${destNum}");

		Map<String, Route> routes = new LinkedHashMap<>();
		routes.put("1816", new Route("sip:${destNum}@kc.carrier.example.com")
				.addHeader("X-Customer-Id", "${customerId}"));
		routes.put("1212", new Route("sip:${destNum}@nyc.carrier.example.com")
				.addHeader("X-Customer-Id", "${customerId}"));
		routes.put("1", new Route("sip:${destNum}@nanp.carrier.example.com")
				.addHeader("X-Customer-Id", "${customerId}"));
		routes.put("44", new Route("sip:${destNum}@uk.carrier.example.com")
				.addHeader("X-Customer-Id", "${customerId}"));

		routes.get("1816").setDescription("Kansas City local carrier");
		routes.get("1212").setDescription("NYC local carrier");
		routes.get("1").setDescription("North America NANP — default carrier");
		routes.get("44").setDescription("United Kingdom");
		routing.setRoutes(routes);

		Route fallback = new Route("sip:${destNum}@intl.carrier.example.com")
				.addHeader("X-Customer-Id", "${customerId}");
		fallback.setDescription("Unknown prefix — international fallback carrier");
		routing.setDefaultRoute(fallback);

		this.setRouting(routing);
	}
}
