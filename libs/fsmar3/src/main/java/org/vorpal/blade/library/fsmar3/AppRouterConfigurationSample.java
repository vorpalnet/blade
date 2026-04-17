package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.configuration.RequestSelector;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/// Sample FSMAR v3 configuration used to generate the FSMAR3.SAMPLE file.
///
/// Demonstrates the simplified v3 model using [RequestSelector] for pattern
/// matching instead of v2 Conditions.
public class AppRouterConfigurationSample extends AppRouterConfiguration implements Serializable {
	private static final long serialVersionUID = 1L;

	public AppRouterConfigurationSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.WARNING);

		this.setDefaultApplication("b2bua");

		// Initial requests (previous = "null")
		this.getState("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getState("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getState("null").getTrigger("PUBLISH").createTransition("presence");
		this.getState("null").getTrigger("OPTIONS").createTransition("options");

		// INVITE with subscriber identification
		this.getState("null").getTrigger("INVITE").createTransition("keep-alive")
				.setId("INV-1")
				.setSubscriber("From");

		// From keep-alive, INVITE with conditions
		Transition t1 = this.getState("keep-alive").getTrigger("INVITE").createTransition("b2bua");
		t1.setId("INV-2");
		t1.addSelectorGroup().addSelector(new RequestSelector("to-user", "To",
				".*<sips?:(?<user>[^@]+)@(?<host>[^>]+)>.*", "${user}@${host}"));
		t1.setSubscriber("From");
		t1.setRoutes(new String[] { "sip:proxy1", "sip:proxy2" });

		// Unconditional fallback from keep-alive
		this.getState("keep-alive").getTrigger("INVITE").createTransition("b2bua")
				.setId("INV-3");

		// From b2bua, route to proxy-registrar
		this.getState("b2bua").getTrigger("INVITE").createTransition("proxy-registrar");
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
