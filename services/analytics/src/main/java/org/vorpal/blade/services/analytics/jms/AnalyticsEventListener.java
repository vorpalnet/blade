package org.vorpal.blade.services.analytics.jms;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.MessageDrivenContext;
import javax.ejb.Timeout;
import javax.ejb.Timer;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.TypedQuery;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.services.analytics.model.Attribute;
import org.vorpal.blade.services.analytics.model.AttributeName;
import org.vorpal.blade.services.analytics.model.AttributePK;
import org.vorpal.blade.services.analytics.model.Event;
import org.vorpal.blade.services.analytics.model.EventType;
import org.vorpal.blade.services.analytics.model.SessionKey;
import org.vorpal.blade.services.analytics.model.SessionKeyPK;
import org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet;

import com.fasterxml.jackson.databind.JsonNode;

/// The analytics database, as one subscriber on the BLADE event bus.
///
/// **This is a sink, and it is shaped differently from every other consumer on
/// purpose.** An *actor* — a transfer app acting on a refer — names the types it
/// handles and lets the broker filter, so it never wakes for an event it would
/// ignore, and it fails closed: a type nobody listed is never enqueued for it.
/// This one names nothing and takes everything, deciding per message from the
/// catalog's `persist` flags. It fails *open*: a type marked persisted this
/// afternoon is recorded this afternoon, with no regeneration and no redeploy.
/// The price is real and worth stating — this subscription's store holds events
/// this consumer will drop, against the destination's quota — but for the one
/// consumer whose job is to miss nothing, that is the right side of the trade.
///
/// **The subscription identity is this subscriber's name, not any event's.** Two
/// applications wanting the same event each need their own, or they are not two
/// subscriptions at all: they are one subscription named twice, and the two apps
/// compete for a single stream instead of each receiving a copy. See
/// [BladeEventCatalog#ANALYTICS_SUBSCRIPTION], which is where this name is
/// declared so the catalog and this file cannot disagree.
///
/// **What it replaced.** `AnalyticsJmsListener`, which read Java-serialized JPA
/// entities off a queue of its own and discriminated on them with `instanceof` —
/// the mechanism that required every consumer to be on BLADE's classpath and made
/// selector-based routing impossible. The good parts of it survive here: the
/// database-down backpressure that suspends delivery through JMX rather than
/// discarding, the health-check timer that resumes it, and the interning caches
/// for event and attribute names.
@TransactionManagement(TransactionManagementType.BEAN)
@MessageDriven(mappedName = "jms/BladeEventBusTopic", activationConfig = {
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
		// Named explicitly. Left out, the container falls back to a default
		// connection factory — which works, and quietly means the factory the bus
		// provisions (and its prefetch settings) applies to nothing.
		@ActivationConfigProperty(propertyName = "connectionFactoryJndiName", propertyValue = "jms/BladeEventBusConnectionFactory"),
		@ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
		// Both are this SUBSCRIPTION's name, never an event type's.
		@ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "analytics-db"),
		@ActivationConfigProperty(propertyName = "clientId", propertyValue = "analytics-db"),
		@ActivationConfigProperty(propertyName = "topicMessagesDistributionMode", propertyValue = "One-Copy-Per-Application"),
		@ActivationConfigProperty(propertyName = "distributedDestinationConnection", propertyValue = "EveryMember"),
		// No messageSelector, deliberately: see the class comment.
		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge") })
public class AnalyticsEventListener implements MessageListener {

	/// Must match this class's simple name. The MDB finds its own runtime MBean
	/// through this to suspend and resume itself, and two MDBs sharing a simple
	/// class name would both match — one application's backpressure would stop
	/// another's delivery.
	private static final String MDB_MBEAN_QUERY = "com.bea:Type=MessageDrivenEJBRuntime,Name=AnalyticsEventListener,*";

	private EntityManagerFactory emf;
	private static Logger sipLogger;
	private static volatile boolean databaseDown = false;
	private static Timer healthCheckTimer;

	/// Events this subscription received but the catalog does not mark persisted.
	///
	/// Counted rather than ignored: taking everything means dropping most of it
	/// is normal, and without a number "analytics is missing events" has no
	/// answer. Logged on a decade boundary so the log says something occasionally
	/// without saying it per message.
	private static final LongAdder dropped = new LongAdder();
	private static final LongAdder persisted = new LongAdder();

	@Resource
	private MessageDrivenContext mdbContext;

	@Resource
	private TimerService timerService;

	@PostConstruct
	public void init() {
		sipLogger = SettingsManager.getSipLogger();
		try {
			emf = Persistence.createEntityManagerFactory("BladeAnalytics");
		} catch (Exception e) {
			logSevere("AnalyticsEventListener: Failed to create EntityManagerFactory: " + e.getMessage(), e);
			reportDatabaseDown();
		}
	}

	@PreDestroy
	public void cleanup() {
		if (emf != null && emf.isOpen()) {
			emf.close();
		}
	}

	@Override
	public void onMessage(Message message) {

		if (sipLogger == null) {
			sipLogger = SettingsManager.getSipLogger();
		}

		// Safety net for the brief window between setting databaseDown and the
		// JMX suspend taking effect. Discarding is right here and only here: the
		// subscription is durable, so anything not yet delivered is being held.
		if (databaseDown) {
			logWarning("AnalyticsEventListener: Database unavailable, discarding message");
			return;
		}

		if (!(message instanceof TextMessage)) {
			logWarning("AnalyticsEventListener: Received non-TextMessage, ignoring");
			return;
		}

		if (emf == null) {
			try {
				emf = Persistence.createEntityManagerFactory("BladeAnalytics");
			} catch (Exception e) {
				logSevere("AnalyticsEventListener: Cannot create EntityManagerFactory: " + e.getMessage(), e);
				reportDatabaseDown();
				return;
			}
		}

		EntityManager em = null;
		try {
			CloudEvent event = CloudEvent.fromJson(((TextMessage) message).getText());
			String type = event.getType();

			if (!AnalyticsCatalog.persists(type)) {
				dropped.increment();
				long total = dropped.sum();
				if (total % 10_000 == 0) {
					logInfo("AnalyticsEventListener: " + total + " events not marked persisted have been "
							+ "dropped; " + persisted.sum() + " written. Most recent: " + type);
				}
				return;
			}

			em = emf.createEntityManager();
			em.getTransaction().begin();
			persist(em, event, type);
			em.getTransaction().commit();
			persisted.increment();

		} catch (Exception ex) {
			if (em != null) {
				try {
					if (em.getTransaction().isActive()) {
						em.getTransaction().rollback();
					}
				} catch (Exception rollbackEx) {
					logSevere("AnalyticsEventListener: Rollback failed: " + rollbackEx.getMessage(), rollbackEx);
				}
			}

			if (isDatabaseConnectionError(ex)) {
				logSevere("AnalyticsEventListener: Database connection error: " + ex.getMessage(), ex);
				closeEmf();
				reportDatabaseDown();
			} else {
				// Bad data or a constraint violation. Consume it: a malformed
				// payload will not fix itself on redelivery, and forcing one would
				// spin this event forever ahead of every event behind it.
				logSevere("AnalyticsEventListener: Persist error: " + ex.getMessage(), ex);
			}
		} finally {
			if (em != null && em.isOpen()) {
				em.close();
			}
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
		event.setEventUid(cloudEvent.getId());
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
		// travels in the payload under the generic type. Either way the
		// event_types table stores the short name, as it always has, so existing
		// reports keep working.
		String name = data.hasNonNull("eventName") ? data.path("eventName").asText() : Wire.shortName(type);
		event.setName(name);
		event.setEventTypeId(lookupEventTypeId(em, name));

		em.persist(event);
		em.flush();

		long eventId = event.getId();
		JsonNode attributes = data.path("attributes");
		int count = 0;
		if (attributes.isArray()) {
			for (JsonNode pair : attributes) {
				String attrName = pair.path("name").asText(null);
				if (attrName == null) {
					continue;
				}
				Attribute attribute = new Attribute();
				attribute.setId(new AttributePK(eventId, lookupAttributeNameId(em, attrName)));
				attribute.setValue(Wire.truncate(pair.path("value").asText(""), 1024));
				em.persist(attribute);
				count++;
			}
		}

		logFine("AnalyticsEventListener: Event id=" + eventId + " name=" + name + " attributes=" + count);
	}

	// ───────────────────────────────────────────────────────── wire helpers

	/// Resolve the publishing application instance from the identity every
	/// call-scoped event carries.
	private static Long applicationId(EntityManager em, JsonNode data) {
		return ApplicationResolver.resolveOrCreate(em, Wire.text(data, "appName"), Wire.text(data, "domain"),
				Wire.text(data, "server"), Wire.instant(data, "appStartedAt"), null, null);
	}

	// ────────────────────────────────────────────── backpressure and health

	private void reportDatabaseDown() {
		synchronized (AnalyticsEventListener.class) {
			if (!databaseDown) {
				databaseDown = true;
				logSevere("AnalyticsEventListener: Database failure detected, suspending message delivery", null);
				suspendMessageDelivery();
				startHealthCheckTimer();
			}
		}
	}

	private void startHealthCheckTimer() {
		if (healthCheckTimer == null) {
			int healthCheckInterval = AnalyticsSipServlet.settingsManager.getCurrent().healthCheckInterval * 1000;
			healthCheckTimer = timerService.createIntervalTimer(healthCheckInterval, healthCheckInterval,
					new TimerConfig("dbHealthCheck", false));
			logWarning("AnalyticsEventListener: Started health check timer (" + healthCheckInterval + "ms interval)");
		}
	}

	@Timeout
	public void checkDatabaseHealth(Timer timer) {
		synchronized (AnalyticsEventListener.class) {
			if (testDatabaseConnection()) {
				databaseDown = false;
				healthCheckTimer = null;
				timer.cancel();
				resumeMessageDelivery();
				logWarning("AnalyticsEventListener: Database connection restored, resumed message delivery");
			}
		}
	}

	/// Suspend delivery to this MDB through WebLogic JMX.
	///
	/// **A durable subscription is what makes this work.** Suspended delivery
	/// means events accumulate in the subscription's store rather than being
	/// dropped, so a database outage costs latency instead of rows. It also means
	/// a long outage draws on the destination's quota — which is shared with
	/// every other subscriber, so a database down for a day can start failing
	/// publishes for applications that have nothing to do with analytics.
	private void suspendMessageDelivery() {
		invokeOnSelf("suspend");
	}

	private void resumeMessageDelivery() {
		invokeOnSelf("resume");
	}

	private void invokeOnSelf(String operation) {
		try {
			MBeanServer mbs = (MBeanServer) new InitialContext().lookup("java:comp/env/jmx/runtime");
			Set<ObjectName> mbeans = mbs.queryNames(new ObjectName(MDB_MBEAN_QUERY), null);
			for (ObjectName name : mbeans) {
				mbs.invoke(name, operation, null, null);
				logWarning("AnalyticsEventListener: JMS message delivery " + operation + "ed via JMX (" + name + ")");
			}
		} catch (Exception e) {
			logSevere("AnalyticsEventListener: Failed to " + operation + " message delivery via JMX: "
					+ e.getMessage(), e);
		}
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
		String id = AnalyticsSipServlet.settingsManager.getCurrent().domainId;
		return (id != null && !id.isEmpty()) ? id : SettingsManager.getDomainName();
	}

	// ─────────────────────────────────────────── name interning, unchanged

	// Static, not per-instance. WebLogic pools MDB instances, so instance-level
	// caches meant the synchronized lookup below did not even cover one JVM —
	// two pooled instances could race each other on the UNIQUE(name) constraint,
	// and the loser's violation took the whole event down with it. Static at
	// least makes the lock mean what it looks like it means. It still does not
	// coordinate across nodes; a duplicate-key failure is treated as "someone
	// else won" and re-read.
	private static final ConcurrentMap<String, Short> eventTypeCache = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, Short> attributeNameCache = new ConcurrentHashMap<>();

	private short lookupEventTypeId(EntityManager em, String name) {
		Short id = eventTypeCache.get(name);
		if (id != null) {
			return id.shortValue();
		}
		synchronized (eventTypeCache) {
			id = eventTypeCache.get(name);
			if (id != null) {
				return id.shortValue();
			}
			TypedQuery<EventType> q = em.createNamedQuery("EventType.findByName", EventType.class);
			q.setParameter("name", name);
			List<EventType> results = q.getResultList();
			EventType et;
			if (!results.isEmpty()) {
				et = results.get(0);
			} else {
				et = new EventType(name);
				em.persist(et);
				em.flush();
			}
			eventTypeCache.put(name, Short.valueOf(et.getId()));
			return et.getId();
		}
	}

	private short lookupAttributeNameId(EntityManager em, String name) {
		Short id = attributeNameCache.get(name);
		if (id != null) {
			return id.shortValue();
		}
		synchronized (attributeNameCache) {
			id = attributeNameCache.get(name);
			if (id != null) {
				return id.shortValue();
			}
			TypedQuery<AttributeName> q = em.createNamedQuery("AttributeName.findByName", AttributeName.class);
			q.setParameter("name", name);
			List<AttributeName> results = q.getResultList();
			AttributeName an;
			if (!results.isEmpty()) {
				an = results.get(0);
			} else {
				an = new AttributeName(name);
				em.persist(an);
				em.flush();
			}
			attributeNameCache.put(name, Short.valueOf(an.getId()));
			return an.getId();
		}
	}

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
