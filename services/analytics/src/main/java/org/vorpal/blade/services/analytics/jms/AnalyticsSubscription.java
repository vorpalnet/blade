package org.vorpal.blade.services.analytics.jms;

import java.util.List;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.EventSubscription;
import org.vorpal.blade.framework.v3.events.SelectorMode;

/// Owns the analytics subscription's lifetime and keeps it matching the
/// catalog.
///
/// **The subscription is no longer a compile-time fact.** It used to be an
/// `@MessageDriven` class whose selector, destination and durability were
/// annotation constants, which is the whole reason the sink took every event on
/// the bus and threw most of them away: there was no way to widen a selector at
/// runtime, so the only way to be sure of catching a newly-declared type was to
/// catch everything. Here the selector is rebuilt from the catalog's `persist`
/// flags whenever they change, so the sink asks the broker for exactly what it
/// intends to write — and an operator marking a new type persisted still sees
/// rows without anyone redeploying anything.
///
/// The watch interval matches [AnalyticsCatalog]'s own reload throttle. There
/// is no push notification for this file — the Configurator pushes to an
/// application's `SettingsManager`, and this catalog belongs to a different
/// application — so a few seconds' delay after a publish is the cost, which is
/// the right trade for a flag nobody changes during a call.
@WebListener
public class AnalyticsSubscription implements ServletContextListener {

	/// How often to check whether the catalog now wants a different selector.
	private static final long WATCH_INTERVAL_MS = 10_000L;

	private final AnalyticsEventListener handler = new AnalyticsEventListener();
	private volatile boolean running;
	private Thread watchdog;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		handler.start();
		running = true;

		// Reconcile once synchronously so the log line below reports the state
		// the application actually started in, not the state it will reach.
		reconcile(true);

		watchdog = new Thread(() -> {
			while (running) {
				try {
					Thread.sleep(WATCH_INTERVAL_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				reconcile(false);
			}
		}, "blade-analytics-subscription");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		running = false;
		if (watchdog != null) {
			watchdog.interrupt();
		}
		// Close, never unsubscribe: the durable subscription must outlive a
		// redeploy or the broker discards everything it held while the
		// application was down.
		EventBus.unregisterSubscriber(BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
		handler.stop();
	}

	/// Ask the bus to make the live subscription match the catalog. A no-op
	/// when nothing relevant changed, so running it on a timer is cheap.
	private void reconcile(boolean announce) {
		try {
			List<String> types = AnalyticsCatalog.persistedTypes();
			String selector = selectorFor(types);

			// Durable: this is the one consumer that must not miss an event
			// because it happened to be redeploying. The subscriber holds one
			// durable subscription per member of the distributed topic, which
			// is what the logical destination refuses to do — see
			// EventSubscriber.
			boolean rebuilt = EventBus.reconcileSubscriber(
					BladeEventCatalog.ANALYTICS_SUBSCRIPTION,
					EventBus.CONNECTION_FACTORY_JNDI,
					EventBus.TOPIC_JNDI,
					selector,
					true,
					handler);

			if (rebuilt || announce) {
				// Report the consumer count, because zero is the interesting
				// case and it is otherwise invisible: member discovery is
				// asynchronous, so a subscription can be "established" and
				// consuming nothing.
				EventSubscriber live = EventBus.subscriberFor(BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
				log().info("analytics: subscribed to " + EventBus.TOPIC_JNDI + " as '"
						+ BladeEventCatalog.ANALYTICS_SUBSCRIPTION + "', "
						+ (selector == null
								? "taking every event and filtering in code (" + types.size()
										+ " types wanted, too many for a selector)"
								: "filtering at the broker for " + types.size() + " event type"
										+ (types.size() == 1 ? "" : "s"))
						+ (live == null ? "" : "; consumers=" + live.getConsumerCount()));
			}
		} catch (Exception e) {
			// A subscription that cannot be established must say so. Failing
			// quietly here is precisely how a dead pipeline came to look
			// identical to a healthy one.
			log().severe("analytics: could not establish the '" + BladeEventCatalog.ANALYTICS_SUBSCRIPTION
					+ "' subscription — NO EVENTS WILL BE RECORDED: " + e.getMessage());
		}
	}

	/// The broker-side filter for a set of types.
	///
	/// Built through [EventSubscription#selector] rather than assembled here,
	/// so the string this consumer selects on is produced by the same code that
	/// produces every other consumer's — a selector written twice is a selector
	/// that eventually disagrees with what the publisher stamps.
	///
	/// Returns null when the catalog wants more types than a selector will
	/// carry, which puts the sink back to filtering in code. That is a
	/// degradation, not a failure, and the log line above says which one is in
	/// force.
	private static String selectorFor(List<String> types) {
		if (types.isEmpty()) {
			return null;
		}
		EventSubscription subscription = new EventSubscription(BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
		subscription.setSelectorMode(SelectorMode.DERIVED);
		subscription.setTypes(types);
		return subscription.selector();
	}

	private static Logger log() {
		return SettingsManager.getSipLogger();
	}
}
