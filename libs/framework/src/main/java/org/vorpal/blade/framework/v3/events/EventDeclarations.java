package org.vorpal.blade.framework.v3.events;

import java.io.InputStream;
import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Publishes an application's own event declarations to the domain, so they
/// travel with the application instead of being copied into `events.json`.
///
/// An application that adds event types ships their declarations in its WAR
/// as `WEB-INF/blade-events.json`: the same shape as the catalog, an object
/// with a `types` list. On deployment this listener exposes them as
/// `vorpal.blade:Type=EventDeclarations,Name=<application>`, which the Events
/// Console finds across the domain and lists as declared by that application.
/// Undeploying the application removes them. The domain's `events.json` still
/// wins for any type it also declares, so an operator can override a flag
/// without touching the application.
///
/// The analytics sink does not need these to store a call event (see
/// `AnalyticsCatalog.persists`); they describe the types, their fields and
/// their owner.
///
/// Registered for every WAR through the framework's `web-fragment.xml`. An
/// application with no declarations file registers nothing. Never fatal: a
/// malformed file is logged and the application starts without it.
public class EventDeclarations implements ServletContextListener, EventDeclarationsMXBean {

	/// Where an application's declarations live in its WAR.
	public static final String RESOURCE = "/WEB-INF/blade-events.json";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private String application;
	private String catalog;
	private int typeCount;
	private ObjectName objectName;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		ServletContext context = sce.getServletContext();
		try (InputStream in = context.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return;
			}
			EventCatalog declared = MAPPER.readValue(in, EventCatalog.class);
			application = SettingsManager.deriveName(context);
			for (EventType type : declared.typesOrEmpty()) {
				if (type.getOwner() == null || type.getOwner().isEmpty()) {
					type.setOwner(application);
				}
			}
			catalog = MAPPER.writeValueAsString(java.util.Collections.singletonMap("types", declared.typesOrEmpty()));
			typeCount = declared.typesOrEmpty().size();

			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			objectName = objectName(application);
			if (server.isRegistered(objectName)) {
				server.unregisterMBean(objectName);
			}
			server.registerMBean(new StandardMBean(this, EventDeclarationsMXBean.class, true), objectName);
			context.log("events: " + application + " declares " + typeCount + " event type"
					+ (typeCount == 1 ? "" : "s") + " (" + RESOURCE + ")");
		} catch (Exception e) {
			context.log("events: " + RESOURCE + " was not published, and the application runs without it: " + e);
			objectName = null;
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (objectName == null) {
			return;
		}
		try {
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			if (server.isRegistered(objectName)) {
				server.unregisterMBean(objectName);
			}
		} catch (Exception ignored) {
			// The server is going down with the application.
		}
	}

	/// The name an application's declarations are registered under. On the
	/// AdminServer's domain runtime the container appends `Location=<server>`.
	public static ObjectName objectName(String application) throws javax.management.MalformedObjectNameException {
		return new ObjectName("vorpal.blade:Type=EventDeclarations,Name=" + ObjectName.quote(application));
	}

	@Override
	public String getApplication() {
		return application;
	}

	@Override
	public String getCatalog() {
		return catalog;
	}

	@Override
	public int getTypeCount() {
		return typeCount;
	}
}
