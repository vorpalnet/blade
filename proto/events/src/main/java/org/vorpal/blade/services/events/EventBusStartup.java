package org.vorpal.blade.services.events;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventPublisher;

/// Stands up the event bus when the WAR starts: registers the app's
/// SettingsManager (so it appears on the Admin Portal deck and its config is
/// Configurator-editable) and installs the node-local [EventPublisher] into
/// [EventBus].
///
/// This is the pub/sub analog of the analytics opt-in block in
/// `AsyncSipServlet.servletInitialized` — but this app has no SIP servlet, so
/// the publisher is built here in a `@WebListener` off the ServletContext,
/// exactly as the Security admin app builds its SettingsManager. If the JMS
/// resources aren't provisioned yet (see `notes/configure-events-jms.py`),
/// publisher init fails loudly and the ingress simply returns 503 until they
/// are — a producer is never blocked.
@WebListener
public class EventBusStartup implements ServletContextListener {

	/// ServletContext attribute holding a `Supplier<EventBusSettings>` so the
	/// JAX-RS ingress reads the *current* config (Configurator edits take effect
	/// without redeploy).
	public static final String SETTINGS_SUPPLIER_ATTR = "org.vorpal.blade.events.settings";

	private static final Logger logger = Logger.getLogger(EventBusStartup.class.getName());

	private SettingsManager<EventBusSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, EventBusSettings.class, new EventBusSettingsSample());
			Supplier<EventBusSettings> supplier = () -> settingsManager.getCurrent();
			sce.getServletContext().setAttribute(SETTINGS_SUPPLIER_ATTR, supplier);
			logger.info("events SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "events SettingsManager failed to register", e);
		}

		try {
			EventPublisher publisher = new EventPublisher(EventBus.CONNECTION_FACTORY_JNDI, EventBus.TOPIC_JNDI);
			publisher.init();
			EventBus.publisher = publisher;
			logger.info("event bus publisher installed on topic " + EventBus.TOPIC_JNDI);
		} catch (Exception e) {
			logger.log(Level.SEVERE,
					"event bus publisher failed to init. Ensure " + EventBus.CONNECTION_FACTORY_JNDI + " and "
							+ EventBus.TOPIC_JNDI + " are provisioned (notes/configure-events-jms.py).",
					e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		sce.getServletContext().removeAttribute(SETTINGS_SUPPLIER_ATTR);

		EventPublisher publisher = EventBus.publisher;
		if (publisher != null) {
			EventBus.publisher = null;
			try {
				publisher.close();
			} catch (Exception e) {
				logger.log(Level.WARNING, "event bus publisher close error", e);
			}
		}

		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "events SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
