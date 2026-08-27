package org.vorpal.blade.services.analytics.jms;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;

/// Owns the analytics subscription's lifetime.
///
/// **The subscription is no longer a compile-time fact.** It used to be an
/// `@MessageDriven` class whose selector, destination and durability were
/// annotation constants, which is the whole reason the sink took every event on
/// the bus and threw most of them away: there was no way to widen a selector at
/// runtime, so the only way to be sure of catching a newly-declared type was to
/// catch everything. The selector is now rebuilt from the catalog's `persist`
/// flags whenever they change, so the sink asks the broker for exactly what it
/// intends to write — and an operator marking a new type persisted still sees
/// rows without anyone redeploying anything.
///
/// **This sink is the one consumer that supplies its own type list.** An actor
/// names the handful of types it acts on, and [SubscriptionRegistrar] reads
/// those from the catalog's subscription entry. Analytics wants "everything
/// marked persisted", which is a property of the *types* rather than of any
/// subscription, so it passes [AnalyticsCatalog#persistedTypes] instead.
@WebListener
public class AnalyticsSubscription implements ServletContextListener {

	/// Batched, unlike an actor. This consumer writes rows rather than acting,
	/// so it can trade a little latency for one transaction instead of many —
	/// which is safe here only because the keys are computed, so re-applying a
	/// rolled-back batch lands every row exactly where it was going.
	private static final int BATCH_SIZE = EventSubscriber.DEFAULT_BATCH_SIZE;

	private final AnalyticsEventListener handler = new AnalyticsEventListener();
	private SubscriptionRegistrar registrar;
	private org.vorpal.blade.framework.v3.events.EventBusControl control;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		handler.start();
		AnalyticsEventListener.meter(event.getServletContext());
		control = org.vorpal.blade.framework.v3.events.EventBusControl.register(
				org.vorpal.blade.framework.v2.config.SettingsManager.deriveName(event.getServletContext()));
		SubscriptionRegistrar.meter(event.getServletContext(), BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
		registrar = SubscriptionRegistrar.start(
				BladeEventCatalog.ANALYTICS_SUBSCRIPTION,
				AnalyticsCatalog::persistedTypes,
				true,
				handler,
				BATCH_SIZE,
				EventSubscriber.DEFAULT_BATCH_MILLIS);
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		if (registrar != null) {
			registrar.stop();
		}
		if (control != null) {
			control.unregister();
		}
		handler.stop();
	}
}
