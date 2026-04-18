package org.vorpal.blade.services.irouter;

import java.util.LinkedList;

import org.vorpal.blade.framework.v3.configuration.adapters.RestAdapter;
import org.vorpal.blade.framework.v3.configuration.adapters.SipAdapter;
import org.vorpal.blade.framework.v3.configuration.adapters.TableAdapter;
import org.vorpal.blade.framework.v3.configuration.selectors.JsonSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.tables.HashTranslationTable;

/// Sample iRouter configuration written to `_samples/` on first deploy.
///
/// Demonstrates the classic "fraud-screening" flow modeled on the
/// retired `connect-tao/securelogix` service: a SIP request comes in,
/// a per-customer enrichment table supplies the REST credentials, a
/// REST call to an external screening API returns an action, and a
/// final routing table picks the destination based on that action.
///
/// Pipeline order (each step reads context values written by earlier
/// steps):
///
/// 1. **`sip`** ([SipAdapter]) — pulls the remote IP out of the SIP
///    request via a [RegexSelector] on the `Contact` header. The
///    extracted value is stored under `${remoteIP}`.
/// 2. **`customers`** ([TableAdapter] of [CustomerProfile]) — looks
///    up `${remoteIP}` in a hash table of known customers. On match,
///    each field of the matched [CustomerProfile] is spread into the
///    context, making `${customerId}`, `${apiKey}`, and `${baseUrl}`
///    available to the next step.
/// 3. **`screening`** ([RestAdapter]) — POSTs to
///    `${baseUrl}/check` with bearer auth `${apiKey}`. Two
///    [JsonSelector]s parse the response: `$.action` (allow / block)
///    and `$.routeTo` (a SIP URI returned by the API).
/// 4. **`routes`** ([TableAdapter] of [RoutingTreatment]) — the final
///    routing decision. Keyed on `${action}`; `allow` routes to the
///    URI the API supplied, `block` rejects with a local reject URI.
/// 5. **`defaultRoute`** — operator fallback for anything the pipeline
///    fails to match.
public class IRouterConfigSample extends IRouterConfig {
	private static final long serialVersionUID = 1L;

	public IRouterConfigSample() {
		// ----- Step 1: SIP parse -----
		SipAdapter sip = new SipAdapter();
		sip.setId("sip");
		sip.setDescription("Extract the caller's remote IP from the Contact header");
		// Matches the host portion of a Contact URI, storing it as ${remoteIP}.
		sip.addSelector(new RegexSelector("remoteIP", "Contact",
				".*@(?<host>[^:;>]+).*", "${host}"));

		// ----- Step 2: per-customer enrichment table -----
		HashTranslationTable<CustomerProfile> customers = new HashTranslationTable<>();
		customers.setId("customers");
		customers.setDescription("Map inbound remote IP to the customer's REST credentials");
		customers.setKeyExpression("${remoteIP}");

		Translation<CustomerProfile> acme = customers.createTranslation("10.1.2.3");
		acme.setDescription("Acme Corp — premium fraud screening tier");
		acme.setTreatment(new CustomerProfile(
				"acme",
				"acme-api-key-redacted",
				"https://acme.screening.example.com"));

		Translation<CustomerProfile> globex = customers.createTranslation("10.4.5.6");
		globex.setDescription("Globex Inc — standard fraud screening tier");
		globex.setTreatment(new CustomerProfile(
				"globex",
				"globex-api-key-redacted",
				"https://globex.screening.example.com"));

		TableAdapter<CustomerProfile> customersAdapter = new TableAdapter<>(customers);
		customersAdapter.setId("customers-lookup");
		customersAdapter.setDescription("Fetch this customer's REST credentials");

		// ----- Step 3: external screening REST call -----
		RestAdapter screening = new RestAdapter();
		screening.setId("screening");
		screening.setDescription("Ask the customer's screening API whether to allow the call");
		screening.setUrl("${baseUrl}/check");
		screening.setMethod("POST");
		screening.setBearerToken("${apiKey}");
		screening.setBodyTemplate("screening-request.tmpl");
		screening.setTimeoutSeconds(3);

		JsonSelector action = new JsonSelector();
		action.setId("action");
		action.setDescription("Screening verdict: allow | block");
		action.setAttribute("$.action");
		screening.addSelector(action);

		JsonSelector routeTo = new JsonSelector();
		routeTo.setId("routeTo");
		routeTo.setDescription("Destination SIP URI supplied by the screening API");
		routeTo.setAttribute("$.routeTo");
		screening.addSelector(routeTo);

		// ----- Step 4: routing decision -----
		HashTranslationTable<RoutingTreatment> routes = new HashTranslationTable<>();
		routes.setId("routes");
		routes.setDescription("Translate the screening verdict into a proxy destination");
		routes.setKeyExpression("${action}");

		routes.createTranslation("allow")
				.setTreatment(new RoutingTreatment("${routeTo}")
						.addHeader("X-Customer-Id", "${customerId}"));
		routes.createTranslation("block")
				.setTreatment(new RoutingTreatment("sip:rejected@local.reject"));

		TableAdapter<RoutingTreatment> routesAdapter = new TableAdapter<>(routes);
		routesAdapter.setId("routes-lookup");
		routesAdapter.setDescription("Final routing decision — last TableAdapter in the pipeline");

		// ----- Assemble the pipeline -----
		this.setPipeline(new LinkedList<>());
		this.getPipeline().add(sip);
		this.getPipeline().add(customersAdapter);
		this.getPipeline().add(screening);
		this.getPipeline().add(routesAdapter);

		// ----- Default fallback -----
		Translation<RoutingTreatment> fallback = new Translation<>("operator");
		fallback.setDescription("Fallback when the pipeline produces no routing decision");
		fallback.setTreatment(new RoutingTreatment("sip:operator@pbx.example.com"));
		this.setDefaultRoute(fallback);
	}
}
