package org.vorpal.blade.framework.v3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.FeatureContext;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

class RestJsonDiscoverableTest {

	/// A FeatureContext that records properties and registrations.
	static final class Recorder {
		final Map<String, Object> properties = new HashMap<>();
		final List<Class<?>> registered = new ArrayList<>();

		FeatureContext context() {
			Configuration config = (Configuration) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { Configuration.class }, (proxy, method, args) -> {
						switch (method.getName()) {
						case "getProperty":
							return properties.get(args[0]);
						case "isRegistered":
							return registered.contains(args[0]);
						default:
							throw new UnsupportedOperationException(method.getName());
						}
					});
			return (FeatureContext) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { FeatureContext.class }, (proxy, method, args) -> {
						switch (method.getName()) {
						case "getConfiguration":
							return config;
						case "property":
							properties.put((String) args[0], args[1]);
							return proxy;
						case "register":
							registered.add((Class<?>) args[0]);
							return proxy;
						default:
							throw new UnsupportedOperationException(method.getName());
						}
					});
		}
	}

	@Test
	void claimsTheJsonFeatureAndRegistersTheMapperOnce() {
		Recorder recorder = new Recorder();
		FeatureContext context = recorder.context();
		new RestJsonDiscoverable().configure(context);
		new RestJsonDiscoverable().configure(context);
		assertEquals(RestJsonDiscoverable.CLAIM, recorder.properties.get(RestJsonDiscoverable.JSON_FEATURE));
		assertEquals(List.of(RestJson.class, com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider.class),
				recorder.registered);
	}

	/// A scanning application already has blade-shared's writers; no second one.
	@Test
	void aScannedJacksonWriterIsNotDoubled() {
		Recorder recorder = new Recorder();
		recorder.registered.add(com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider.class);
		new RestJsonDiscoverable().configure(recorder.context());
		assertEquals(List.of(com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider.class, RestJson.class),
				recorder.registered);
	}

	@Test
	void anApplicationsOwnChoiceStands() {
		Recorder recorder = new Recorder();
		recorder.properties.put(RestJsonDiscoverable.JSON_FEATURE, "JacksonFeature");
		new RestJsonDiscoverable().configure(recorder.context());
		assertEquals("JacksonFeature", recorder.properties.get(RestJsonDiscoverable.JSON_FEATURE));
	}

	@JsonPropertyOrder({ "zeta", "alpha", "beta" })
	public static class Thing {
		public String zeta = "z";
		public String alpha = null;
		public String beta = "b";
	}

	@Test
	void theMapperKeepsDeclaredOrderAndDropsNulls() throws Exception {
		assertEquals("{\"zeta\":\"z\",\"beta\":\"b\"}",
				new RestJson().getContext(Thing.class).writeValueAsString(new Thing()));
	}
}
