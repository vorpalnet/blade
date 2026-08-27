package org.vorpal.blade.framework.v3.events;

import java.util.List;
import java.util.function.Supplier;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/// Keeps one consumer's subscription matching the catalog for as long as the
/// application is deployed.
///
/// **This is the piece that replaces `@MessageDriven` for an application.** An
/// MDB's activation config is a set of compile-time constants, so the only way
/// to change what a consumer listens to was to regenerate its source and
/// redeploy its WAR. A consumer built on this declares what it wants once, and
/// an operator can change it in the catalog afterwards.
///
/// A consuming application starts one of these per subscription from a
/// `ServletContextListener` and stops it on the way out. [EventSourceGenerator]
/// emits exactly that, so a generated consumer and a hand-written one have the
/// same shape.
///
/// ## What it does on a tick
///
/// Ask for the types wanted now, build the broker-side selector from them, and
/// hand both to [EventBus#reconcileSubscriber], which rebuilds the subscription
/// only if something that matters actually changed. Everything else is
/// reporting: a subscription that cannot be established says so loudly and
/// keeps trying, because a consumer silently receiving nothing is the failure
/// this whole area kept producing.
public final class SubscriptionRegistrar {

	/// How often to check whether the catalog now wants something different.
	/// Matches [EventCatalogFile]'s own reload throttle — checking faster than
	/// the catalog can change would only re-read the same answer.
	private static final long WATCH_INTERVAL_MS = 10_000L;

	private final String subscriptionName;
	private final Supplier<List<String>> types;
	private final java.util.function.BooleanSupplier durable;
	private final EventSubscriber.Handler handler;
	private final int batchSize;
	private final long batchMillis;

	private volatile boolean running;
	private Thread watchdog;

	private SubscriptionRegistrar(String subscriptionName, Supplier<List<String>> types,
			java.util.function.BooleanSupplier durable, EventSubscriber.Handler handler, int batchSize,
			long batchMillis) {
		this.subscriptionName = subscriptionName;
		this.types = types;
		this.durable = durable;
		this.handler = handler;
		this.batchSize = batchSize;
		this.batchMillis = batchMillis;
	}

	/// Start a subscription whose wanted types come from the catalog, falling
	/// back to what the consumer was built with.
	///
	/// This is what a generated consumer calls. The fallback is what makes a
	/// fresh domain work: with no `events.json` published, the consumer still
	/// subscribes to the types it was generated for.
	///
	/// @param subscriptionName this SUBSCRIBER's name, never an event type's
	/// @param declaredTypes    the types the consumer was built for
	/// @param handler          what to do with each batch
	public static SubscriptionRegistrar start(String subscriptionName, List<String> declaredTypes,
			EventSubscriber.Handler handler) {
		Supplier<List<String>> wanted = () -> {
			List<String> fromCatalog = EventCatalogFile.declaredTypes(subscriptionName);
			return (fromCatalog != null) ? fromCatalog : declaredTypes;
		};
		// Durability follows the catalog when the operator has declared this
		// subscription, because it is an operational decision rather than a
		// property of the code: a durable actor that has been down for a while
		// wakes to stale facts it should probably not act on, and whether that
		// matters depends on what the actor does.
		java.util.function.BooleanSupplier durable = () -> {
			EventSubscription declared = EventCatalogFile.declaredSubscription(subscriptionName);
			return (declared == null) || declared.isDurable();
		};
		// One event per transaction: an actor acts, and acting on a batch that
		// may be rolled back and re-delivered as a unit is a different contract
		// from writing rows.
		return start(subscriptionName, wanted, durable, handler, 1, EventSubscriber.DEFAULT_BATCH_MILLIS);
	}

	/// Start a subscription whose wanted types are computed by the caller.
	///
	/// The analytics sink uses this: its types are every type the catalog marks
	/// persisted, which is not a subscription entry anyone writes by hand.
	///
	/// @param types       asked on every tick for the types wanted now
	/// @param durable     whether the subscription survives a restart
	/// @param batchSize   events per transaction; 1 for an actor, more for a sink
	/// @param batchMillis how long a partial batch waits before committing
	public static SubscriptionRegistrar start(String subscriptionName, Supplier<List<String>> types,
			boolean durable, EventSubscriber.Handler handler, int batchSize, long batchMillis) {
		return start(subscriptionName, types, () -> durable, handler, batchSize, batchMillis);
	}

	private static SubscriptionRegistrar start(String subscriptionName, Supplier<List<String>> types,
			java.util.function.BooleanSupplier durable, EventSubscriber.Handler handler, int batchSize,
			long batchMillis) {

		SubscriptionRegistrar registrar = new SubscriptionRegistrar(subscriptionName, types, durable, handler,
				batchSize, batchMillis);
		registrar.running = true;

		// Reconcile once synchronously, so the log line reports the state the
		// application actually started in rather than one it will reach.
		registrar.reconcile(true);

		registrar.watchdog = new Thread(() -> {
			while (registrar.running) {
				try {
					Thread.sleep(WATCH_INTERVAL_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				registrar.reconcile(false);
			}
		}, "blade-subscription-" + subscriptionName);
		registrar.watchdog.setDaemon(true);
		registrar.watchdog.start();
		return registrar;
	}

	/// Stop watching and close the subscription.
	///
	/// The durable subscription itself is left registered with the broker: that
	/// is what makes it durable, and it is how events accumulate for a consumer
	/// being redeployed rather than retired.
	public void stop() {
		running = false;
		if (watchdog != null) {
			watchdog.interrupt();
		}
		EventBus.unregisterSubscriber(subscriptionName);
	}

	/// Never throws.
	///
	/// It used to, and the consequence was worse than the failure it was
	/// reporting: this runs from `contextInitialized`, so an exception escaping
	/// here fails the whole application's deployment. A consumer that cannot
	/// reach the bus yet should say so and keep trying on the next tick — the
	/// bus may simply not be provisioned on this domain — not take the
	/// application down with it.
	private void reconcile(boolean announce) {
		try {
			List<String> wanted = types.get();

			if (wanted == null || wanted.isEmpty()) {
				// Stop consuming rather than subscribe with no selector. They
				// are opposite meanings that would otherwise collide here: an
				// empty type list is an operator saying "none", while a null
				// selector tells the broker "send everything". Getting this
				// backwards would turn switching a consumer off into
				// subscribing it to the entire bus.
				if (EventBus.unregisterSubscriber(subscriptionName) || announce) {
					warning("events: '" + subscriptionName + "' wants no event types and is not "
							+ "consuming. Declare types for it in the catalog to switch it back on.");
				}
				return;
			}

			String selector = selectorFor(subscriptionName, wanted);

			boolean rebuilt = EventBus.reconcileSubscriber(subscriptionName, EventBus.CONNECTION_FACTORY_JNDI,
					EventBus.TOPIC_JNDI, selector, durable.getAsBoolean(), handler, batchSize, batchMillis);

			if (rebuilt || announce) {
				EventSubscriber live = EventBus.subscriberFor(subscriptionName);
				info("events: '" + subscriptionName + "' subscribed to " + EventBus.TOPIC_JNDI + ", "
						+ (selector == null
								? "taking every event and filtering in code (" + wanted.size()
										+ " types wanted, too many for a selector)"
								: "filtering at the broker for " + wanted.size() + " event type"
										+ (wanted.size() == 1 ? "" : "s"))
						+ (live == null ? "" : "; consumers=" + live.getConsumerCount()));
			}
		} catch (Throwable t) {
			severe("events: could not establish the '" + subscriptionName + "' subscription — THIS "
					+ "CONSUMER IS RECEIVING NOTHING: " + t);
		}
	}

	/// The broker-side filter for a set of types.
	///
	/// Built through [EventSubscription#selector] rather than assembled here,
	/// so every consumer's selector is produced by the code that also decides
	/// what the publisher stamps. A selector written twice is a selector that
	/// eventually disagrees.
	private static String selectorFor(String subscriptionName, List<String> types) {
		if (types == null || types.isEmpty()) {
			return null;
		}
		EventSubscription subscription = new EventSubscription(subscriptionName);
		subscription.setSelectorMode(SelectorMode.DERIVED);
		subscription.setTypes(types);
		return subscription.selector();
	}

	/// Logging that works in an application with no SIP servlet.
	///
	/// `SettingsManager.getSipLogger()` returns null until a SIP servlet has
	/// initialized, and some applications on this bus have none at all —
	/// `services/events` is HTTP and JMS only. A null logger here is how a
	/// failure report turned into a NullPointerException *inside the catch
	/// block that was reporting it*, which then escaped `contextInitialized`
	/// and failed the deployment. The diagnostic must never be the thing that
	/// breaks.
	private static void info(String message) {
		Logger sip = SettingsManager.getSipLogger();
		if (sip != null) {
			sip.info(message);
		} else {
			FALLBACK.info(message);
		}
	}

	private static void warning(String message) {
		Logger sip = SettingsManager.getSipLogger();
		if (sip != null) {
			sip.warning(message);
		} else {
			FALLBACK.warning(message);
		}
	}

	private static void severe(String message) {
		Logger sip = SettingsManager.getSipLogger();
		if (sip != null) {
			sip.severe(message);
		} else {
			FALLBACK.severe(message);
		}
	}

	private static final java.util.logging.Logger FALLBACK =
			java.util.logging.Logger.getLogger(SubscriptionRegistrar.class.getName());
}
