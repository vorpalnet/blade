package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;

import javax.servlet.sip.ar.SipRouteModifier;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v3.configuration.RequestSelector;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/// Sample FSMAR v3 configuration used to generate the FSMAR3.SAMPLE file.
///
/// Demonstrates the simplified v3 model using [RequestSelector] for pattern
/// matching instead of v2 Conditions.
public class AppRouterConfigurationSample2 extends AppRouterConfiguration implements Serializable {
	private static final long serialVersionUID = 1L;

	public AppRouterConfigurationSample2() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.WARNING);

		this.setDefaultApplication("b2bua");

		// Reusable selector library. Each entry is referenced by name from
		// any SelectorGroup via `selectorRefs`, keeping the transitions
		// compact and the matching rules centralized.
		this.addSelector("to-is-bob", new RequestSelector(
				"to-is-bob", "To",
				".*<sips?:(?<user>[^@]+)@(?<host>[^>]+)>.*",
				"${user}",
				"bob"));
		this.addSelector("from-is-alice", new RequestSelector(
				"from-is-alice", "From",
				".*<sips?:(?<user>[^@]+)@(?<host>[^>]+)>.*",
				"${user}",
				"alice"));

		// Initial requests (previous = "null")
		this.getState("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getState("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getState("null").getTrigger("PUBLISH").createTransition("presence");
		this.getState("null").getTrigger("OPTIONS").createTransition("options");

		// INVITE with subscriber identification
		this.getState("null").getTrigger("INVITE").createTransition("keep-alive")
				.setId("INV-1")
				.setSubscriber("From");

		// INVITE from keep-alive when both named selectors match (to=bob AND from=alice).
		// Uses `selectorRefs` instead of inlining the selector bodies.
		Transition t1 = this.getState("keep-alive").getTrigger("INVITE").createTransition("b2bua");
		t1.setId("INV-2");
		t1.addSelectorGroup()
				.addSelectorRef("to-is-bob")
				.addSelectorRef("from-is-alice");
		t1.setSubscriber("From");
		t1.setRoutes(new String[] { "sip:proxy1", "sip:proxy2" });

		// Unconditional fallback from keep-alive
		this.getState("keep-alive").getTrigger("INVITE").createTransition("b2bua")
				.setId("INV-3");

		// From b2bua, route to proxy-registrar
		this.getState("b2bua").getTrigger("INVITE").createTransition("proxy-registrar");

		// ROUTE_BACK example: pushes routes behind the destination application
		// so they are visited on the response path. Either use the convenience
		// helper setRouteBack(...) or call setRoutes(...) + setRouteModifier(ROUTE_BACK).
		this.getState("b2bua").getTrigger("INVITE").createTransition("transfer")
				.setId("INV-4")
				.setRouteBack(new String[] { "sip:recorder1", "sip:recorder2" });

		// ROUTE_FINAL example: equivalent shorthand using the explicit modifier.
		this.getState("b2bua").getTrigger("INVITE").createTransition("hold")
				.setId("INV-5")
				.setRoutes(new String[] { "sip:finalproxy" })
				.setRouteModifier(SipRouteModifier.ROUTE_FINAL);
	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfigurationSample2 configuration = new AppRouterConfigurationSample2();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
