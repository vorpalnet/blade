package org.vorpal.blade.services.events;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.sip.ServletParseException;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventPublisher;
import org.vorpal.blade.framework.v3.events.EventType;

/// Owns the event catalog on this engine node and keeps the bus's publishers in
/// line with it.
///
/// The framework calls [#initialize] on every config reload — including the
/// initial load during construction — so a destination added or removed through
/// the Events console takes effect the moment the catalog is published, with no
/// redeploy. That is the whole reason this is a `SettingsManager` subclass
/// rather than a one-shot read at startup.
///
/// **Reload is a diff, not a rebuild.** Publishers for destinations that did not
/// change are left strictly alone. Tearing every publisher down and standing it
/// back up on each reload would close JMS connections underneath in-flight
/// publishes for destinations the operator never touched — an edit to one event
/// type would disturb all of them.
///
/// A destination that cannot be reached is logged and skipped, not fatal. The
/// JMS resources may simply not be provisioned yet, and a producer must never be
/// blocked by that: [EventBus#publish] no-ops when no publisher is installed and
/// the ingress answers 503, so the failure is visible without being contagious.
public class EventCatalogSettingsManager extends SettingsManager<EventCatalog> {

	private static final Logger logger = Logger.getLogger(EventCatalogSettingsManager.class.getName());

	public EventCatalogSettingsManager(ServletContextEvent event) throws Exception {
		super(event, EventCatalog.class, new EventCatalogSample());
	}

	/// Framework hook, invoked on every `reload()`. Brings the installed
	/// publishers in line with the destinations the catalog names.
	///
	/// Deliberately no field initializers in this class: this runs inside the
	/// super constructor, before subclass fields would be assigned.
	@Override
	public synchronized void initialize(EventCatalog catalog) throws ServletParseException {
		if (catalog == null) {
			logger.warning("no event catalog loaded; the bus will publish nothing on this node");
			return;
		}

		EventBus.setDefaultDestinationJndi(catalog.getDefaultDestinationJndi());

		Set<String> wanted = destinationsIn(catalog);
		Set<String> installed = EventBus.registeredDestinations();

		for (String destination : installed) {
			if (!wanted.contains(destination)) {
				EventBus.unregister(destination);
				logger.info("event bus stopped publishing to " + destination + " (no longer in the catalog)");
			}
		}

		for (String destination : wanted) {
			if (installed.contains(destination)) {
				continue;
			}
			try {
				EventPublisher publisher = new EventPublisher(catalog.getConnectionFactoryJndi(), destination);
				publisher.init();
				EventBus.register(publisher);
				logger.info("event bus publishing to " + destination);
			} catch (Exception e) {
				logger.log(Level.SEVERE,
						"event bus could not publish to " + destination + ". Ensure "
								+ catalog.getConnectionFactoryJndi() + " and " + destination
								+ " are provisioned — the Events console can create them, or use"
								+ " notes/configure-messaging-jms.py.",
						e);
			}
		}
	}

	/// Every distinct destination the catalog names: the default, plus any an
	/// event type binds itself to.
	private static Set<String> destinationsIn(EventCatalog catalog) {
		Set<String> destinations = new HashSet<>();
		if (catalog.getDefaultDestinationJndi() != null && !catalog.getDefaultDestinationJndi().isEmpty()) {
			destinations.add(catalog.getDefaultDestinationJndi());
		}
		for (EventType declaration : catalog.typesOrEmpty()) {
			String destination = catalog.destinationFor(declaration);
			if (destination != null && !destination.isEmpty()) {
				destinations.add(destination);
			}
		}
		return destinations;
	}
}
