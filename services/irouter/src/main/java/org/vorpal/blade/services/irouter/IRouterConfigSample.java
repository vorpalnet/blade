package org.vorpal.blade.services.irouter;

import java.util.LinkedList;

import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.auth.BearerAuthentication;
import org.vorpal.blade.framework.v3.configuration.connectors.RestConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.TableConnector;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalRouting;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.selectors.JsonSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

/// Sample iRouter configuration written to `_samples/` on first deploy.
///
/// The sample is the canonical teaching artifact for the v3 router
/// model — it exercises every feature iRouter offers so the JSON in
/// `_samples/irouter.json.SAMPLE` and the auto-generated
/// `_schemas/irouter.jschema` document the full surface for operators.
///
/// ## Scenario: time-aware, tiered call screening
///
/// 1. Parse the INVITE for remote IP, caller, callee, and From-domain.
/// 2. Identify the customer via a three-table fallback chain (by IP,
///    else by source area code, else by From-domain). The matched
///    translation's extras — `customerId`, `apiKey`, `customerTier`,
///    `screeningUrl` — flow into the Context.
/// 3. Look up the current `shift` (business / evening / overnight)
///    from the caller's local hour via a **range-matched** table.
/// 4. Call the customer's screening API via REST (Bearer auth; the
///    `${apiKey}` comes from the customers table).
/// 5. Make the routing decision via a **ConditionalRouting**: the
///    screening action, current shift, and customer tier combine to
///    pick an outbound Route. One clause stamps a **conditional
///    `X-Priority: high`** header only when the caller is a premium
///    customer.
///
/// Features exercised:
///
/// - Four [RegexSelector]s on [SipConnector] (remote IP, caller,
///   callee, From-domain).
/// - [TableConnector] with a three-entry multi-table fallback chain of
///   [TranslationTable]s — hash on IP, prefix on source number, hash
///   on From-domain.
/// - [TranslationTable] with [MatchStrategy#range] for the
///   time-of-day shift lookup.
/// - [RestConnector] with [BearerAuthentication], body template, two
///   [JsonSelector]s.
/// - [ConditionalRouting] with four clauses using `&&` / `||` /
///   comparisons on the enriched Context.
/// - [org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader]
///   stamping `X-Priority: high` only for premium customers.
///
/// For OAuth-protected screening APIs, swap [BearerAuthentication] for
/// one of the five `OAuth2*Authentication` subtypes — the
/// `RestConnector.authentication` field is polymorphic and the
/// Configurator reshapes the form when the `type` dropdown changes.
public class IRouterConfigSample extends IRouterConfig {
	private static final long serialVersionUID = 1L;

	public IRouterConfigSample() {

		// ----- Pipeline step 1: SIP parse -----
		SipConnector sip = new SipConnector();
		sip.setId("sip");
		sip.setDescription("Extract remote IP, caller, callee, and From-domain");
		sip.addSelector(new RegexSelector("remoteIP", "remoteIP", ".*", "${0}"));
		sip.addSelector(new RegexSelector("srcNum", "From", ".*<sips?:\\+?(?<ani>\\d+).*", "${ani}"));
		sip.addSelector(new RegexSelector("destNum", "To", ".*<sips?:\\+?(?<did>\\d+).*", "${did}"));
		sip.addSelector(new RegexSelector("fromHost", "From", ".*@(?<host>[^:;>]+).*", "${host}"));

		// ----- Pipeline step 2: customer enrichment with fallback chain -----
		TableConnector customers = new TableConnector();
		customers.setId("customers");
		customers.setDescription("Find this customer via IP, else source number, else From-domain");

		TranslationTable byRemoteIp = new TranslationTable();
		byRemoteIp.setDescription("Known customer IPs (most specific)");
		byRemoteIp.setMatch(MatchStrategy.hash);
		byRemoteIp.setKeyExpression("${remoteIP}");
		byRemoteIp.createTranslation("172.16.32.173")
				.put("customerId", "acme")
				.put("customerTier", "premium")
				.put("apiKey", "acme-api-key-redacted")
				.put("screeningUrl", "https://acme.screening.example.com");
		byRemoteIp.createTranslation("10.4.5.6")
				.put("customerId", "globex")
				.put("customerTier", "standard")
				.put("apiKey", "globex-api-key-redacted")
				.put("screeningUrl", "https://globex.screening.example.com");
		customers.addTable(byRemoteIp);

		TranslationTable bySrcAreaCode = new TranslationTable();
		bySrcAreaCode.setDescription("Customers identified by source area code (longest-prefix match)");
		bySrcAreaCode.setMatch(MatchStrategy.prefix);
		bySrcAreaCode.setKeyExpression("${srcNum}");
		bySrcAreaCode.createTranslation("1816")
				.put("customerId", "kcco")
				.put("customerTier", "standard")
				.put("apiKey", "kcco-api-key-redacted")
				.put("screeningUrl", "https://kcco.screening.example.com");
		customers.addTable(bySrcAreaCode);

		TranslationTable byFromHost = new TranslationTable();
		byFromHost.setDescription("Customers identified by SIP From-domain (catch-all)");
		byFromHost.setMatch(MatchStrategy.hash);
		byFromHost.setKeyExpression("${fromHost}");
		byFromHost.createTranslation("example.com")
				.put("customerId", "exampleCo")
				.put("customerTier", "standard")
				.put("apiKey", "example-api-key-redacted")
				.put("screeningUrl", "https://example.screening.example.com");
		customers.addTable(byFromHost);

		// ----- Pipeline step 3: shift-of-day via range match -----
		// ${now:H} resolves to the current UTC hour (00..23). The range
		// table buckets it into a named shift that downstream routing
		// clauses reference.
		TableConnector schedule = new TableConnector();
		schedule.setId("schedule");
		schedule.setDescription("Classify the current hour as business / evening / overnight");

		TranslationTable shifts = new TranslationTable();
		shifts.setDescription("Business hours by UTC hour (range match)");
		shifts.setMatch(MatchStrategy.range);
		shifts.setKeyExpression("${now:H}");
		shifts.createTranslation("0-7").put("shift", "overnight");
		shifts.createTranslation("8-17").put("shift", "business");
		shifts.createTranslation("18-23").put("shift", "evening");
		schedule.addTable(shifts);

		// ----- Pipeline step 4: external screening REST call -----
		RestConnector screening = new RestConnector();
		screening.setId("screening");
		screening.setDescription("Ask the customer's screening API whether to allow the call");
		screening.setUrl("${screeningUrl}/request");
		screening.setMethod("POST");
		screening.setAuthentication(new BearerAuthentication("${apiKey}"));
		screening.setBodyTemplate("template.txt");
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

		// ----- Assemble the pipeline -----
		this.setPipeline(new LinkedList<>());
		this.getPipeline().add(sip);
		this.getPipeline().add(customers);
		this.getPipeline().add(schedule);
		this.getPipeline().add(screening);

		// ----- Routing decision: if/elif/else chain -----
		// Walked top-to-bottom; first clause whose `when` is true wins.
		// Premium customers during business hours get an X-Priority
		// header stamped conditionally.
		ConditionalRouting routing = new ConditionalRouting();

		routing.addClause(
				"${action} == block",
				new Route("sip:rejected@pbx.example.com")
						.addHeader("X-Customer-Id", "${customerId}")
						.addHeader("X-Screening", "blocked"));
		routing.getClauses().get(0).getRoute().setDescription("Blocked by screening");

		routing.addClause(
				"${action} == allow && ${shift} == business",
				new Route("${routeTo}")
						.addHeader("X-Customer-Id", "${customerId}")
						.addHeader("X-Screening", "passed")
						.addConditionalHeader("X-Priority", "high",
								"${customerTier} == premium"));
		routing.getClauses().get(1).getRoute()
				.setDescription("Allowed, business hours — route to the screening-suggested destination");

		routing.addClause(
				"${action} == allow",
				new Route("sip:voicemail@pbx.example.com")
						.addHeader("X-Customer-Id", "${customerId}")
						.addHeader("X-Original-Dest", "${routeTo}"));
		routing.getClauses().get(2).getRoute()
				.setDescription("Allowed, outside business hours — send to voicemail");

		Route fallback = new Route("sip:operator@pbx.example.com")
				.addHeader("X-Customer-Id", "${customerId}");
		fallback.setDescription("Fallback when no clause matches");
		routing.setDefaultRoute(fallback);

		this.setRouting(routing);
	}
}
