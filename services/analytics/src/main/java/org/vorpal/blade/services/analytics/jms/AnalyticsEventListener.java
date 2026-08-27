package org.vorpal.blade.services.analytics.jms;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import javax.naming.InitialContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.services.analytics.model.Event;
import org.vorpal.blade.services.analytics.model.SessionKey;
import org.vorpal.blade.services.analytics.model.SessionKeyPK;
import org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The analytics database, as one subscriber on the BLADE event bus. This class
/// is the *handler*; [AnalyticsSubscription] owns when it runs and what it
/// receives.
///
/// **It used to take every event on the bus and throw most of them away.** Not
/// carelessly — it was the only option. As an `@MessageDriven` class its
/// selector was an annotation constant, so a type marked persisted in the
/// console could not reach a running consumer; taking everything was how a sink
/// whose job is to miss nothing avoided missing anything. It paid for that in
/// broker storage (this subscription's store held events it would discard,
/// against a quota shared with every other subscriber) and in a delivery per
/// discarded event. With the subscription owned in code and rebuilt on change,
/// the sink asks the broker for exactly the types it intends to write and
/// still tracks the catalog.
///
/// **The subscription identity is this subscriber's name, not any event's.** Two
/// applications wanting the same event each need their own, or they are not two
/// subscriptions at all: they are one subscription named twice, and the two apps
/// compete for a single stream instead of each receiving a copy. See
/// [BladeEventCatalog#ANALYTICS_SUBSCRIPTION], which is where this name is
/// declared so the catalog and this file cannot disagree.
///
/// **Failure is held, not swallowed.** A batch that cannot be written is not
/// acknowledged, so the broker redelivers it; a database that is unreachable
/// pauses the subscription entirely and lets the backlog accumulate on the
/// broker's file store until a health check says it is back. The previous
/// version logged a persistence failure and acknowledged the message anyway,
/// which meant any database trouble that was not a connection error destroyed
/// events while every other indicator said the pipeline was healthy.
///
/// **What it replaced originally.** `AnalyticsJmsListener`, which read
/// Java-serialized JPA entities off a queue of its own and discriminated on them
/// with `instanceof` — the mechanism that required every consumer to be on
/// BLADE's classpath and made selector-based routing impossible.
public class AnalyticsEventListener implements EventSubscriber.Handler {

	private EntityManagerFactory emf;
	private static Logger sipLogger;
	private static volatile boolean databaseDown = false;

	/// Events that arrived anyway and the catalog does not mark persisted.
	///
	/// This should now be near zero — the broker filters — so unlike before it
	/// is a number worth looking at rather than background noise. A rising
	/// count means the live subscription and the catalog disagree: either the
	/// selector could not be built and the sink is filtering in code, or a
	/// `persist` flag was cleared and the subscription has not caught up yet.
	private static final LongAdder dropped = new LongAdder();
	private static final LongAdder persisted = new LongAdder();

	/// Builds the `events.payload` document. Thread-safe once configured, which
	/// is what the JSON library documents and what a pooled consumer needs.
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// The same two numbers as [#persisted] and [#dropped], where an operator
	/// can see them.
	///
	/// The adders alone were only ever reported when `dropped` crossed a
	/// multiple of 10,000 — which, now that the broker filters, may never
	/// happen at all. "How many rows has this written" should not require
	/// reading a log, so they are registered with the application's metrics
	/// alongside `events.published` and friends.
	private static volatile org.vorpal.blade.framework.v3.metrics.Counter.Series persistedMeter;
	private static volatile org.vorpal.blade.framework.v3.metrics.Counter.Series droppedMeter;

	/// Register the write counters. Never fatal: a sink that cannot count must
	/// still record.
	public static void meter(javax.servlet.ServletContext context) {
		try {
			org.vorpal.blade.framework.v3.metrics.MetricsRegistry metrics =
					org.vorpal.blade.framework.v3.metrics.MetricsRegistry.from(context);
			if (metrics == null) {
				return;
			}
			persistedMeter = metrics.counter("analytics.persisted",
					"Events written to the analytics database").series();
			droppedMeter = metrics.counter("analytics.dropped",
					"Events received but not marked persisted by the catalog. Near zero once the "
							+ "broker filters; a rising count means the subscription and the catalog "
							+ "disagree.").series();
		} catch (Throwable t) {
			logWarning("AnalyticsEventListener: write counters unavailable: " + t);
		}
	}

	private static void count(org.vorpal.blade.framework.v3.metrics.Counter.Series series, int times) {
		if (series != null) {
			for (int i = 0; i < times; i++) {
				series.increment();
			}
		}
	}

	public AnalyticsEventListener() {
	}

	/// Write through a caller-supplied factory instead of the container's.
	///
	/// The seam exists so the write path can be exercised without a domain.
	/// Everything this class does that is worth testing — resolving a parent
	/// before its child, surviving a redelivery, adopting an open session when
	/// the wire carries no birth instant — needed a live database to observe,
	/// which is how an insert-ordering bug reached a production deploy.
	AnalyticsEventListener(EntityManagerFactory factory) {
		this.emf = factory;
	}

	/// Open the persistence unit. Called once when the subscription starts.
	public void start() {
		sipLogger = SettingsManager.getSipLogger();
		if (emf != null) {
			return;
		}
		try {
			emf = Persistence.createEntityManagerFactory("BladeAnalytics");
		} catch (Exception e) {
			logSevere("AnalyticsEventListener: Failed to create EntityManagerFactory: " + e.getMessage(), e);
			reportDatabaseDown();
		}
	}

	/// Close the persistence unit. Called when the application shuts down.
	public void stop() {
		closeEmf();
	}

	/// Write one batch, or throw so the broker redelivers it.
	///
	/// **Everything in the batch commits or nothing does.** Re-applying a batch
	/// whose middle failed is safe because every key in this schema is computed
	/// from the event's own identity, so a row written twice is written to the
	/// same place — see `framework v3.analytics.NaturalKey`. That property is what makes
	/// batching worth doing at all: without it, a rollback would have to be a
	/// per-event affair and the round trip per row would come straight back.
	@Override
	public void handle(java.util.List<CloudEvent> batch) throws Exception {
		if (sipLogger == null) {
			sipLogger = SettingsManager.getSipLogger();
		}
		if (emf == null) {
			emf = Persistence.createEntityManagerFactory("BladeAnalytics");
		}

		EntityManager em = emf.createEntityManager();
		try {
			em.getTransaction().begin();
			int written = 0;
			for (CloudEvent event : batch) {
				String type = event.getType();
				if (!AnalyticsCatalog.persists(type)) {
					// Should be rare now that the selector filters at the
					// broker: this is the window between an operator clearing
					// a `persist` flag and the subscription being rebuilt.
					dropped.increment();
					count(droppedMeter, 1);
					continue;
				}
				persist(em, event, type);
				written++;
			}
			em.getTransaction().commit();
			persisted.add(written);
			count(persistedMeter, written);

		} catch (Exception ex) {
			rollback(em);

			if (isDatabaseConnectionError(ex)) {
				// Not this batch's fault, and not something redelivery fixes
				// quickly. Stop consuming and let the durable subscription hold
				// the backlog on the broker's file store until the database is
				// back — burning the destination's redelivery limit during an
				// outage would send good events to the error destination.
				logSevere("AnalyticsEventListener: Database connection error: " + ex.getMessage(), ex);
				closeEmf();
				reportDatabaseDown();
			} else {
				logSevere("AnalyticsEventListener: Persist error, batch of " + batch.size()
						+ " will be redelivered: " + ex.getMessage(), ex);
			}
			// Either way the batch is NOT acknowledged. This is the difference
			// from the previous consumer, which logged and acknowledged — so a
			// database hiccup that was not a connection error destroyed events
			// while reporting itself as healthy.
			throw ex;
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}
	}

	private void rollback(EntityManager em) {
		try {
			if (em.getTransaction().isActive()) {
				em.getTransaction().rollback();
			}
		} catch (Exception rollbackEx) {
			logSevere("AnalyticsEventListener: Rollback failed: " + rollbackEx.getMessage(), rollbackEx);
		}
	}

	/// Write one event, dispatching on its CloudEvents type.
	///
	/// The framework's lifecycle and session types get their own tables; every
	/// call and transfer type — the eleven BLADE names plus the operator-defined
	/// fallback — becomes an `events` row.
	private void persist(EntityManager em, CloudEvent event, String type) {
		switch (String.valueOf(type)) {
		case BladeEventTypes.APPLICATION_STARTED:
			persistApplicationStart(em, event);
			break;
		case BladeEventTypes.APPLICATION_STOPPED:
			persistApplicationStop(em, event);
			break;
		case BladeEventTypes.SESSION_STARTED:
			persistSessionStart(em, event);
			break;
		case BladeEventTypes.SESSION_STOPPED:
			persistSessionStop(em, event);
			break;
		case BladeEventTypes.SESSION_KEY:
			persistSessionKey(em, event);
			break;
		default:
			// Every call and transfer type, and anything else an operator has
			// marked persisted. `eventName` names it when the publisher had no
			// type of its own for it; otherwise the type is the name.
			persistEvent(em, event, type);
			break;
		}
	}

	// ───────────────────────────────────────────────────────── the handlers

	private void persistApplicationStart(EntityManager em, CloudEvent event) {
		JsonNode data = event.getData();
		Long id = ApplicationResolver.resolveOrCreate(em, Wire.text(data, "appName"), Wire.text(data, "domain"),
				Wire.text(data, "server"), Wire.instant(data, "appStartedAt"), Wire.text(data, "host"), Wire.text(data, "tenant"));
		logFine("AnalyticsEventListener: Application start id=" + id + " " + Wire.text(data, "appName"));
	}

	private void persistApplicationStop(EntityManager em, CloudEvent event) {
		JsonNode data = event.getData();
		Long id = ApplicationResolver.close(em, Wire.text(data, "appName"), Wire.text(data, "domain"), Wire.text(data, "server"),
				Wire.instant(data, "appStartedAt"), Wire.instant(data, "stoppedAt"));
		logFine("AnalyticsEventListener: Application stop id=" + id);
	}

	private void persistSessionStart(EntityManager em, CloudEvent event) {
		JsonNode data = event.getData();
		Long pk = SessionResolver.resolveOrCreate(em, domainId(), Wire.vorpalId(data), Wire.instant(data, "startedAt"),
				applicationId(em, data).longValue());
		logFine("AnalyticsEventListener: Session start id=" + pk);
	}

	private void persistSessionStop(EntityManager em, CloudEvent event) {
		JsonNode data = event.getData();
		Date stopped = Wire.instant(data, "stoppedAt");
		Long pk = SessionResolver.close(em, domainId(), Wire.vorpalId(data), Wire.instant(data, "startedAt"),
				applicationId(em, data).longValue(),
				(stopped == null) ? new Timestamp(System.currentTimeMillis()) : new Timestamp(stopped.getTime()));
		logFine("AnalyticsEventListener: Session stop id=" + pk);
	}

	/// An index key attached to a call.
	///
	/// **Carries the application identity, which it did not used to.** The old
	/// wire object had only the correlator, so this path resolved the session
	/// with `applicationId = 0` — a stub insert that violates `session_fk1`
	/// unless a row with id 0 happens to exist. The declaration now requires the
	/// four application fields, so a key that arrives before its session start
	/// creates a session that is actually valid.
	private void persistSessionKey(EntityManager em, CloudEvent event) {
		JsonNode data = event.getData();
		Long pk = SessionResolver.resolveOrCreate(em, domainId(), Wire.vorpalId(data), Wire.instant(data, "startedAt"),
				applicationId(em, data).longValue());

		SessionKeyPK id = new SessionKeyPK();
		id.setSessionId(pk.longValue());
		id.setName(Wire.text(data, "name"));
		id.setValue(Wire.truncate(Wire.text(data, "value"), 128));

		SessionKey key = new SessionKey();
		key.setId(id);
		// Composite PK (session_id, name, value): merge is idempotent, which a
		// durable subscription's redeliveries make routine.
		em.merge(key);
		logFine("AnalyticsEventListener: SessionKey session_id=" + pk + " name=" + Wire.text(data, "name"));
	}

	private void persistEvent(EntityManager em, CloudEvent cloudEvent, String type) {
		JsonNode data = cloudEvent.getData();

		Event event = new Event();
		// The CloudEvent id is this row's identity, so it cannot be absent.
		// CloudEvents requires it and the HTTP ingress fills one in, but a
		// producer that skipped it would otherwise take the whole message down;
		// mint one instead and say so. Such an event is not replay-safe, which
		// is the point of naming it in the log.
		String uid = cloudEvent.getId();
		if (uid == null || uid.isEmpty()) {
			uid = UUID.randomUUID().toString();
			logWarning("AnalyticsEventListener: event of type " + type
					+ " arrived with no CloudEvent id; minted " + uid
					+ " — this row cannot dedupe on redelivery");
		}
		event.setEventUid(uid);
		event.setApplicationId(applicationId(em, data).longValue());

		// The domain time, not the envelope's `time`. Getting this wrong shifts
		// every analytics timestamp by the publish latency, silently.
		Date occurredAt = Wire.instant(data, "occurredAt");
		event.setCreated((occurredAt == null) ? new Date() : occurredAt);

		// A sessionless event is legal: the correlator is optional on these
		// declarations precisely so a logging gap does not become a failed
		// publish on the SIP container thread.
		if (data.hasNonNull("vorpalId")) {
			event.setSessionId(SessionResolver.resolveOrCreate(em, domainId(), Wire.vorpalId(data),
					Wire.instant(data, "startedAt"), event.getApplicationId()));
		}

		// The framework's own names travel as the type; an operator's name
		// travels in the payload under the generic type. Either way the short
		// name lands in the `type` column, so existing reports keep working.
		String name = data.hasNonNull("eventName") ? data.path("eventName").asText() : Wire.shortName(type);
		event.setType(name);

		ObjectNode payload = MAPPER.createObjectNode();
		JsonNode attributes = data.path("attributes");
		if (attributes.isArray()) {
			for (JsonNode pair : attributes) {
				String attrName = pair.path("name").asText(null);
				if (attrName == null) {
					continue;
				}
				payload.put(attrName, Wire.truncate(pair.path("value").asText(""), 1024));
			}
		}
		event.setPayload(payload.toString());

		// Skip an event already recorded, rather than inserting it again.
		//
		// **A deterministic key makes the row predictable; it does not make the
		// write idempotent.** `persist` on an existing primary key throws — so
		// without this check every redelivery fails, gets redelivered, fails
		// again, and is eventually parked on the error destination. And
		// redelivery is routine here, not exceptional: a durable subscription
		// replays on every rolling restart, and a batch that fails partway is
		// redelivered whole, including the events in it that succeeded.
		//
		// An event is an immutable fact, so a row that exists needs nothing
		// done to it. The lookup is a primary-key find, served from the
		// persistence context for anything already touched in this batch.
		if (em.find(Event.class, event.getId()) != null) {
			logFine("AnalyticsEventListener: event " + event.getEventUid() + " already recorded");
			return;
		}

		// No flush. The key came from the CloudEvent id before the insert, and
		// nothing downstream needs to read it back — which is what forced a
		// mid-transaction flush here before.
		em.persist(event);

		logFine("AnalyticsEventListener: Event id=" + event.getId() + " type=" + name
				+ " attributes=" + payload.size());
	}

	// ───────────────────────────────────────────────────────── wire helpers

	/// Resolve the publishing application instance from the identity every
	/// call-scoped event carries.
	private static Long applicationId(EntityManager em, JsonNode data) {
		return ApplicationResolver.resolveOrCreate(em, Wire.text(data, "appName"), Wire.text(data, "domain"),
				Wire.text(data, "server"), Wire.instant(data, "appStartedAt"), null, null);
	}

	// ────────────────────────────────────────────── backpressure and health

	/// Stop consuming until the database answers again.
	///
	/// **A durable subscription is what makes this work.** A subscription that
	/// is not being consumed accumulates on the broker's file store rather than
	/// being dropped, so a database outage costs latency instead of rows. It
	/// also means a long outage draws on the destination's quota — which is
	/// shared with every other subscriber, so a database down for a day can
	/// start failing publishes for applications that have nothing to do with
	/// analytics. The destination's time-to-live bounds that.
	///
	/// This used to suspend the MDB through a WebLogic JMX operation, found by
	/// querying for this class's own runtime MBean by simple class name. The
	/// subscription is owned directly now, so pausing it is a method call — and
	/// the fragile part of the old arrangement goes away with it: two consumers
	/// sharing a simple class name both matched that JMX query, and one
	/// application's backpressure would have stopped another application's
	/// delivery.
	private void reportDatabaseDown() {
		synchronized (AnalyticsEventListener.class) {
			if (databaseDown) {
				return;
			}
			databaseDown = true;
			logSevere("AnalyticsEventListener: database failure detected; pausing the subscription."
					+ " Events accumulate on the broker until it recovers.", null);

			EventSubscriber subscriber = EventBus.subscriberFor(BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
			if (subscriber != null) {
				subscriber.pause();
			}
			startHealthCheck();
		}
	}

	/// Poll the database until it answers, then resume consuming.
	///
	/// One thread, created only during an outage and ended by it. It replaces
	/// an EJB interval timer, which is not available outside a bean and would
	/// have been the only thing still tying this class to the container.
	private void startHealthCheck() {
		int intervalMs = Math.max(1, AnalyticsSipServlet.settingsManager.getCurrent().healthCheckInterval) * 1000;
		Thread health = new Thread(() -> {
			while (databaseDown) {
				try {
					Thread.sleep(intervalMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (testDatabaseConnection()) {
					synchronized (AnalyticsEventListener.class) {
						databaseDown = false;
						EventSubscriber subscriber =
								EventBus.subscriberFor(BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
						if (subscriber != null) {
							subscriber.resume();
						}
					}
					logWarning("AnalyticsEventListener: database connection restored, resumed consuming");
					return;
				}
			}
		}, "blade-analytics-health");
		health.setDaemon(true);
		health.start();
		logWarning("AnalyticsEventListener: started health check (" + intervalMs + "ms interval)");
	}

	private boolean testDatabaseConnection() {
		try {
			DataSource ds = (DataSource) new InitialContext().lookup("jdbc/BladeAnalytics");
			try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
				stmt.execute(AnalyticsSipServlet.settingsManager.getCurrent().healthCheckSql);
				return true;
			}
		} catch (Exception e) {
			logSevere("AnalyticsEventListener: Connection test failed: " + e.getMessage(), e);
			return false;
		}
	}

	/// Walk the cause chain to tell a connectivity failure from a data failure.
	/// The two want opposite responses: hold everything, or drop this one.
	private boolean isDatabaseConnectionError(Exception ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof java.sql.SQLTransientConnectionException
					|| cause instanceof java.sql.SQLNonTransientConnectionException
					|| cause instanceof java.net.ConnectException || cause instanceof java.net.SocketException) {
				return true;
			}
			String msg = cause.getMessage();
			if (msg != null) {
				String lower = msg.toLowerCase();
				if (lower.contains("communications link failure") || lower.contains("connection refused")
						|| lower.contains("no operations allowed after connection closed")
						|| lower.contains("could not connect") || lower.contains("socket closed")
						|| lower.contains("connection reset")) {
					return true;
				}
			}
			cause = cause.getCause();
		}
		return false;
	}

	private void closeEmf() {
		if (emf != null && emf.isOpen()) {
			try {
				emf.close();
			} catch (Exception e) {
				logWarning("AnalyticsEventListener: Error closing EntityManagerFactory: " + e.getMessage());
			}
		}
		emf = null;
	}

	/// This analytics server's hosting-environment id, from the service config
	/// (`analytics.json`), falling back to the WebLogic domain name. Stamped as
	/// `cluster_name` on every session row so a shared analytics database can tell
	/// environments apart — several clusters may all be called `SIPREC`.
	private static String domainId() {
		// Defensive on every hop. This is read while writing a session row, and
		// the SIP half of this application may not have initialized when the
		// first event arrives — the subscription starts from a servlet context
		// listener, which the container may run first. An NPE here would fail
		// the batch for a reason that has nothing to do with the batch.
		try {
			if (AnalyticsSipServlet.settingsManager != null
					&& AnalyticsSipServlet.settingsManager.getCurrent() != null) {
				String id = AnalyticsSipServlet.settingsManager.getCurrent().domainId;
				if (id != null && !id.isEmpty()) {
					return id;
				}
			}
		} catch (Throwable t) {
			// Fall through to the domain name.
		}
		String domain = SettingsManager.getDomainName();
		return (domain != null && !domain.isEmpty()) ? domain : "unknown";
	}

	// The name-interning caches that used to live here are gone with the
	// `event_types` and `attribute_names` tables they fronted.
	//
	// They are worth a note because their failure mode was subtle and cost a
	// day. Each cache was filled after `em.flush()` but inside the caller's
	// transaction, so when anything later in the same message rolled back, the
	// row vanished and the id stayed cached. Every subsequent event then
	// referenced a lookup row that no longer existed and died on the foreign
	// key — permanently, until the application was redeployed, and with an
	// error naming the foreign key rather than the original failure. Storing
	// the name in the event row removes the cache, the cross-node race on
	// UNIQUE(name), and this.

	// ─── Defensive logging — sipLogger may be null if SettingsManager hasn't ────
	// been initialized yet (the SIP servlet half of services/analytics owns it).
	// All logging goes through these so a secondary NPE in the logger never masks
	// the real underlying error.

	private static void logSevere(String msg, Throwable t) {
		Logger l = sipLogger;
		if (l != null) {
			try {
				l.severe(msg);
				if (t instanceof Exception) {
					l.severe((Exception) t);
				}
				return;
			} catch (Throwable inner) {
				// fall through to System.err
			}
		}
		System.err.println("[AnalyticsEventListener] SEVERE: " + msg);
		if (t != null) {
			t.printStackTrace(System.err);
		}
	}

	private static void logWarning(String msg) {
		Logger l = sipLogger;
		if (l != null) {
			try {
				l.warning(msg);
				return;
			} catch (Throwable inner) {
				// fall through
			}
		}
		System.err.println("[AnalyticsEventListener] WARNING: " + msg);
	}

	private static void logInfo(String msg) {
		Logger l = sipLogger;
		if (l != null) {
			try {
				l.info(msg);
				return;
			} catch (Throwable inner) {
				// fall through
			}
		}
		System.out.println("[AnalyticsEventListener] INFO: " + msg);
	}

	/// Per-row logging at FINE, not INFO.
	///
	/// The old consumer logged a serialized dump of every entity it persisted at
	/// INFO — several lines per event, eight or so events per answered call. At
	/// any real call rate the log is the bottleneck and nothing in it is
	/// readable.
	private static void logFine(String msg) {
		Logger l = sipLogger;
		if (l != null) {
			try {
				l.fine(msg);
			} catch (Throwable inner) {
				// nothing useful to do
			}
		}
	}
}
