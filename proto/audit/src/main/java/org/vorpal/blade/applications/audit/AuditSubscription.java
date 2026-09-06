package org.vorpal.blade.applications.audit;

import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;
import org.vorpal.blade.framework.v3.security.AuditSink;

/// Writes down every access decision, so §164.312(b) has an *examining* half.
///
/// [org.vorpal.blade.framework.v3.security.AccessEvent] has published a record
/// for every decision for some time, and until this existed nothing subscribed:
/// the record reached the bus and was gone. Every access was evaluated and
/// nothing could be shown to an auditor.
///
/// ## What it subscribes to, and what it does not
///
/// The two access types and nothing else. Permits and refusals both, because a
/// log of successes cannot show attempted overreach, and a run of denials against
/// one recording is the signal an access review exists to find.
///
/// It deliberately does not ride the analytics subscription. Analytics records
/// what a call did; this records what a person did. Different readers, different
/// retention, different integrity requirements, which is why the framework marks
/// both access types `persist=false`.
///
/// ## Durable, so the subscription is durable
///
/// The subscription is durable and the batch is small. A durable subscription
/// keeps records while this application is down instead of dropping them, which
/// is the difference between a gap in an audit trail and a hole in it. A small
/// batch bounds how much is in flight and therefore how much is redelivered after
/// a failure.
///
/// [AuditRecorder] rethrows anything the sink refuses, so a batch that was not
/// stored is redelivered rather than acknowledged.
///
/// ## It refuses to start without a sink
///
/// No sink means no subscription. Running without one would consume the records
/// and discard them, which is worse than not running: it looks like compliance
/// and produces nothing. The startup failure is loud and the events stay queued
/// on a durable subscription until somebody installs a sink.
@WebListener
public class AuditSubscription implements ServletContextListener {

	/// The subscription's name on the broker. Durable subscriptions are addressed
	/// by name, so changing it abandons whatever the old one was holding.
	static final String SUBSCRIPTION = "blade-audit";

	/// Small on purpose: see the class note on redelivery.
	private static final int BATCH_SIZE = 16;

	/// Every access decision, permitted and denied.
	static List<String> auditedTypes() {
		return Arrays.asList(BladeEventTypes.ACCESS_PERMITTED, BladeEventTypes.ACCESS_DENIED);
	}

	private static SettingsManager<AuditSettings> settings;

	/// The loaded settings, for the read API. Null before the listener runs.
	static AuditSettings settings() {
		return (settings == null) ? null : settings.getCurrent();
	}

	private final AuditRecorder handler = new AuditRecorder();
	private SubscriptionRegistrar registrar;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		try {
			settings = new SettingsManager<>(event, AuditSettings.class, new AuditSettingsSample());
		} catch (Exception e) {
			// The read API refuses everything without a policy, which is the right
			// failure. The subscription below still starts: losing the ability to
			// READ the log is not a reason to stop WRITING it.
			throw new IllegalStateException("blade-audit could not load its settings", e);
		}
		if (AuditSink.installed() == null) {
			// Loud, and no subscription. See the class note.
			throw new IllegalStateException("blade-audit will not start without an AuditSink on the classpath: "
					+ "access records would be consumed and discarded");
		}
		SubscriptionRegistrar.meter(event.getServletContext(), SUBSCRIPTION);
		registrar = SubscriptionRegistrar.start(
				SUBSCRIPTION,
				AuditSubscription::auditedTypes,
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
	}
}
