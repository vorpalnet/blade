package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.configuration.selectors.AttributeSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.TableSelector;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/// Sample FSMAR v3 configuration used to generate the FSMAR3.SAMPLE file.
///
/// Demonstrates the data-driven model: each state's selectors extract named
/// values from the request into the routing context (accumulating across hops),
/// and each transition fires on a `when` condition over those values, building
/// `${}`-templated routes. The classic "route Bob differently from Alice" is a
/// single transition, not one per subscriber.
public class AppRouterConfigurationSample extends AppRouterConfiguration implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String SIP_USER = ".*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*";

	// Two ingress SBCs on different subnets, classified by source IP. The
	// `insubnet` operator does real CIDR containment (ipaddress library) — any
	// boundary, IPv4/IPv6 — not string-prefix matching. These match strings are
	// shared by each ingress's dispatch transition (the `when` on "null") AND
	// its diagram marker, so they must stay identical.
	private static final String ATLANTA_MATCH = "${originIP} insubnet '10.20.0.0/16'";
	private static final String DALLAS_MATCH  = "${originIP} insubnet '10.30.0.0/16'";

	public AppRouterConfigurationSample() {
		// FSMAR generation 3 — see AppRouterConfiguration.getVersion().
		this.setVersion(3);

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.WARNING);

		this.setDefaultApplication("b2bua");

		// ----- Initial requests (previous = "null") -----
		State init = this.getState("null");
		// Capture caller/callee user-parts and the originating IP once, up front.
		// These persist (via stateInfo) into every later state on this call.
		init.addSelector(new RegexSelector("From", "From", SIP_USER, null));
		init.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		// Source IP of the inbound request (the origin-IP pseudo-header), used
		// by the ingress dispatch below to classify which SBC a call came from.
		init.addSelector(new AttributeSelector("originIP", "originIP"));

		// Classify the caller — tiering as data. The table rows are what an
		// operator edits; the routing logic below just tests ${tier}. Selectors
		// run in order, so this one reads the ${From.user} the RegexSelector
		// above extracted.
		TranslationTable tierTable = new TranslationTable();
		tierTable.setKeyExpression("${From.user}");
		tierTable.createTranslation("alice").put("tier", "gold");
		tierTable.createTranslation("bob").put("tier", "silver");
		init.addSelector(new TableSelector("customerTier", tierTable));

		init.getTrigger("REGISTER").createTransition("proxy-registrar");
		init.getTrigger("SUBSCRIBE").createTransition("presence");
		init.getTrigger("PUBLISH").createTransition("presence");
		init.getTrigger("OPTIONS").createTransition("options");

		// INVITE: a Bob-specific path built from the extracted callee, a
		// gold-tier fast path, a toll-free regex match, plus an unconditional
		// fallback. ${To.user} / ${From.user} are namespaced by the selector
		// id so two selectors capturing (?<user>…) don't collide. Pseudo-
		// variables are also available in `when` and routes: ${method},
		// ${previousApp}, ${hour}, ${dayOfWeek}, and ${hash100} — a stable
		// per-call 0-99 bucket, so "${hash100} < 5" canaries ~5% of calls.
		// Ingress dispatch FIRST: a call from an SBC subnet is classified into
		// that SBC's entry state (Atlanta / Dallas) and bypasses there to run
		// its own routing. These lead the trigger so source-classified traffic
		// wins first-match, ahead of the default-ingress routing below. The
		// editor renders them as the ingress clouds (see the diagram section)
		// and absorbs these dispatch transitions rather than drawing them.
		init.getTrigger("INVITE").createTransition("Atlanta")
				.setId("dispatch-Atlanta")
				.setWhen(ATLANTA_MATCH);
		init.getTrigger("INVITE").createTransition("Dallas")
				.setId("dispatch-Dallas")
				.setWhen(DALLAS_MATCH);

		init.getTrigger("INVITE").createTransition("b2bua")
				.setId("INV-bob")
				.setWhen("${To.user} == 'bob' && ${From.user} == 'alice'")
				.setSubscriber("From")
				.setRoutes(new String[] { "sip:${To.user}@special-proxy" });
		// Gold callers (classified by the customerTier table) skip screening.
		init.getTrigger("INVITE").createTransition("b2bua")
				.setId("INV-gold")
				.setWhen("${tier} == 'gold'")
				.setSubscriber("From")
				.setRoutes(new String[] { "sip:${From.user}@gold-trunk" });
		// Toll-free callees route by full-string regex.
		init.getTrigger("INVITE").createTransition("b2bua")
				.setId("INV-tollfree")
				.setWhen("${To.user} matches '18(00|88|77|66)\\d{7}'")
				.setSubscriber("To");
		init.getTrigger("INVITE").createTransition("screening")
				.setId("INV-default")
				.setSubscriber("From");

		// ----- After screening (a B2BUA that may rewrite From) -----
		State screening = this.getState("screening");
		// Re-capture From here: the screening app may have rewritten it. The
		// original caller is still available as ${From.user} (carried); the
		// post-screening value is ${callerNow.user}.
		screening.addSelector(new RegexSelector("callerNow", "From", SIP_USER, null));

		// Anonymous callers detour through an external media server for a
		// greeting, then the flow RESUMES at b2bua when the call returns — a
		// ROUTE_BACK egress. `next` is the resume state (b2bua); the container
		// pushes a route back to itself (carrying our state), sends the call to
		// the media server, and re-invokes us so we pick up at b2bua. In the Flow
		// editor this is an egress with a line drawn back to the b2bua state.
		screening.getTrigger("INVITE").createTransition("b2bua")
				.setId("SCR-anon")
				.setWhen("${callerNow.user} == 'anonymous'")
				.setSubscriber("To")
				.setRouteBack(new String[] { "sip:greeting@media.example_co.com" });
		screening.getTrigger("INVITE").createTransition("b2bua")
				.setId("SCR-normal")
				.setSubscriber("To");

		// ----- B2BUA invoked ONCE PER SUBSCRIBER LEG — two states, same app.
		// This is what separating a state's id from its application enables: the
		// states map has two keys ("b2bua", "b2bua-callee") that both run the
		// `b2bua` application, so the call is B2BUA'd for the caller leg and then
		// again for the callee leg, with different routing each time.
		//
		// Caller (originating) leg: run the B2BUA for the caller, then hand off
		// to the callee leg. `next` is the SECOND b2bua state's id.
		State b2buaCaller = this.getState("b2bua");
		b2buaCaller.getTrigger("INVITE").createTransition("b2bua-callee")
				.setId("B2B-caller-leg")
				.setSubscriber("From");

		// Callee (terminating) leg: a distinct state id, same `app`. Off-net
		// (E.164) callees EGRESS to the carrier (a terminal transition, no
		// `next` — the call leaves OCCAS); on-net callees are delivered to the
		// registrar.
		State b2buaCallee = this.getState("b2bua-callee").setApp("b2bua");
		b2buaCallee.getTrigger("INVITE").createTransition(null)
				.setId("B2B-offnet")
				.setWhen("${To.user} matches '\\+?1[2-9]\\d{9}'")
				.setSubscriber("To")
				.setRouteFinal(new String[] { "sip:${To.user}@carrier-trunk.example_co.com" });
		b2buaCallee.getTrigger("INVITE").createTransition("proxy-registrar")
				.setId("B2B-deliver")
				.setSubscriber("To")
				.setRoutes(new String[] { "sip:${To.user}@registrar" });

		// ----- Ingress SBCs: two entry states, one per source subnet. Each is
		// a real state — it can carry its OWN selectors and routing. Here each
		// simply routes its calls onward to b2bua via its site gateway, so the
		// ingress an INVITE arrived on determines its egress trunk. -----
		this.getState("Atlanta").getTrigger("INVITE").createTransition("b2bua")
				.setId("ATL-in")
				.setSubscriber("From")
				.setRoutes(new String[] { "sip:${To.user}@atlanta-gw.example_co.com" });
		this.getState("Dallas").getTrigger("INVITE").createTransition("b2bua")
				.setId("DAL-in")
				.setSubscriber("From")
				.setRoutes(new String[] { "sip:${To.user}@dallas-gw.example_co.com" });

		// ----- Diagram: mark Atlanta/Dallas as ingress entry points (with the
		// same source-match). The Flow editor renders them as ingress clouds
		// and absorbs the generated dispatch transitions on "null"; the default
		// ingress is "null" itself. Pure editor metadata — routing ignores it.
		Diagram diagram = new Diagram();
		HashMap<String, Ingress> ingresses = new HashMap<>();
		ingresses.put("Atlanta", new Ingress(ATLANTA_MATCH));
		ingresses.put("Dallas", new Ingress(DALLAS_MATCH));
		diagram.setIngresses(ingresses);

		// Egress exit nodes — the mirror of ingresses, where calls leave OCCAS.
		// The kind is topology: no returnState = ROUTE_FINAL (leaves for good);
		// a returnState = ROUTE_BACK (out to the routes, then resume at that
		// state). Matched to their transitions by (routes, returnState). Pure
		// editor metadata; the AppRouter reads the baked transitions, not this.
		HashMap<String, Egress> egresses = new HashMap<>();
		egresses.put("to-carrier", new Egress(
				new String[] { "sip:${To.user}@carrier-trunk.example_co.com" }, null));
		egresses.put("media-greeting", new Egress(
				new String[] { "sip:greeting@media.example_co.com" }, "b2bua"));
		diagram.setEgresses(egresses);
		this.setDiagram(diagram);
	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfigurationSample configuration = new AppRouterConfigurationSample();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
