package org.vorpal.blade.services.events;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventCatalog;

/// Stands the event bus up when the WAR starts, and tears it down cleanly when
/// it stops.
///
/// All the real work is in [EventCatalogSettingsManager], which registers the
/// catalog with the framework — putting it on the Admin Portal deck and making
/// it Configurator-editable — and installs a publisher per destination the
/// catalog names, re-diffing on every reload.
///
/// This app has no SIP servlet, so the lifecycle hangs off a `@WebListener` on
/// the ServletContext rather than `AsyncSipServlet.servletInitialized`, exactly
/// as the Security admin app does.
@WebListener
public class EventBusStartup implements ServletContextListener {

	/// ServletContext attribute holding a `Supplier<EventCatalog>` so the JAX-RS
	/// ingress reads the *current* catalog on every request — a console or
	/// Configurator edit takes effect without a redeploy.
	public static final String CATALOG_SUPPLIER_ATTR = "org.vorpal.blade.events.catalog";

	private static final Logger logger = Logger.getLogger(EventBusStartup.class.getName());

	private EventCatalogSettingsManager settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new EventCatalogSettingsManager(sce);
			Supplier<EventCatalog> supplier = () -> settingsManager.getCurrent();
			sce.getServletContext().setAttribute(CATALOG_SUPPLIER_ATTR, supplier);
			logger.info("event catalog registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "event catalog failed to register; the bus will not publish on this node", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		sce.getServletContext().removeAttribute(CATALOG_SUPPLIER_ATTR);

		EventBus.unregisterAll();

		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "event catalog unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
