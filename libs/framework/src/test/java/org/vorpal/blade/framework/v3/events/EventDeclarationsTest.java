package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// An application's own declarations reach the domain on deploy and leave on
/// undeploy, with nobody editing `events.json`.
class EventDeclarationsTest {

	private static final String DECLARATIONS = "{\"types\": [{\"type\": \"com.example.shuffle.fraudScored\","
			+ " \"title\": \"Fraud Scored\", \"persist\": true,"
			+ " \"fields\": [{\"name\": \"score\", \"type\": \"INTEGER\"}]}]}";

	/// A servlet context for `/example-app` that serves `resource`, or nothing.
	private static ServletContext context(String resource) {
		return (ServletContext) Proxy.newProxyInstance(EventDeclarationsTest.class.getClassLoader(),
				new Class<?>[] { ServletContext.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "getContextPath":
						return "/example-app";
					case "getResourceAsStream":
						return (resource != null && EventDeclarations.RESOURCE.equals(args[0]))
								? new ByteArrayInputStream(resource.getBytes(StandardCharsets.UTF_8))
								: null;
					default:
						return null;
					}
				});
	}

	@Test
	void declarationsArePublishedOnDeployAndWithdrawnOnUndeploy() throws Exception {
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		ObjectName name = EventDeclarations.objectName("example-app");
		ServletContextEvent event = new ServletContextEvent(context(DECLARATIONS));
		EventDeclarations listener = new EventDeclarations();

		listener.contextInitialized(event);
		try {
			assertTrue(server.isRegistered(name));
			assertEquals("example-app", server.getAttribute(name, "Application"));
			assertEquals(1, server.getAttribute(name, "TypeCount"));
			JsonNode type = new ObjectMapper().readTree((String) server.getAttribute(name, "Catalog"))
					.path("types").get(0);
			assertEquals("com.example.shuffle.fraudScored", type.path("type").asText());
			assertEquals("example-app", type.path("owner").asText(), "an unowned type is owned by its application");
		} finally {
			listener.contextDestroyed(event);
		}
		assertFalse(server.isRegistered(name));
	}

	@Test
	void anApplicationWithoutDeclarationsRegistersNothing() throws Exception {
		EventDeclarations listener = new EventDeclarations();
		ServletContextEvent event = new ServletContextEvent(context(null));
		listener.contextInitialized(event);
		assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(EventDeclarations.objectName("example-app")));
		listener.contextDestroyed(event);
	}

	@Test
	void aMalformedFileDoesNotStopTheApplication() throws Exception {
		EventDeclarations listener = new EventDeclarations();
		ServletContextEvent event = new ServletContextEvent(context("{ not json"));
		listener.contextInitialized(event);
		assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(EventDeclarations.objectName("example-app")));
		listener.contextDestroyed(event);
	}
}
