package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;

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

	public AppRouterConfigurationSample() {
		this.about.setName("FSMAR 3")
				.setTagline("Finite State Machine Application Router")
				.setDescription("Routes initial SIP requests between applications using a finite state machine: "
						+ "states keyed by the previous application, selectors that extract values from the "
						+ "message, and transitions matched by conditions over those values. The future of FSMAR.");

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.WARNING);

		this.setDefaultApplication("b2bua");

		// ----- Initial requests (previous = "null") -----
		State init = this.getState("null");
		// Capture caller/callee user-parts and the originating IP once, up front.
		// These persist (via stateInfo) into every later state on this call.
		init.addSelector(new RegexSelector("From", "From", SIP_USER, null));
		init.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		init.addSelector(new AttributeSelector("callerIp", "Origin-IP"));

		// Classify the caller — tiering as data. The table rows are what an
		// operator edits; the routing logic below just tests ${tier}. Selectors
		// run in order, so this one reads the ${From.user} the RegexSelector
		// above extracted.
		TranslationTable tierTable = new TranslationTable();
		tierTable.setDescription("Customer tier by caller user-part");
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

		screening.getTrigger("INVITE").createTransition("b2bua")
				.setId("SCR-anon")
				.setWhen("${callerNow.user} == 'anonymous'")
				.setSubscriber("To")
				.setRoutes(new String[] { "sip:${To.user}@anon-gw" });
		screening.getTrigger("INVITE").createTransition("b2bua")
				.setId("SCR-normal")
				.setSubscriber("To");

		// ----- After b2bua: deliver to the registrar, route built from the
		// carried callee. -----
		this.getState("b2bua").getTrigger("INVITE").createTransition("proxy-registrar")
				.setId("B2B-deliver")
				.setSubscriber("To")
				.setRoutes(new String[] { "sip:${To.user}@registrar" });
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
