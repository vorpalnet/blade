package org.vorpal.blade.services.analytics.jms;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
import javax.jms.ObjectMessage;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.TypedQuery;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.analytics.Application;
import org.vorpal.blade.framework.v2.analytics.Attribute;
import org.vorpal.blade.framework.v2.analytics.AttributeName;
import org.vorpal.blade.framework.v2.analytics.AttributePK;
import org.vorpal.blade.framework.v2.analytics.Event;
import org.vorpal.blade.framework.v2.analytics.EventType;
import org.vorpal.blade.framework.v2.analytics.Session;
import org.vorpal.blade.framework.v2.analytics.SessionKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet;

/**
 * Message-driven bean that receives JPA entities via JMS ObjectMessages and
 * persists them to the database. When the database is unavailable, JMS message
 * delivery is suspended via WebLogic JMX so messages accumulate in the
 * persistent store. A programmatic timer periodically checks for database
 * recovery and resumes message delivery when the connection is restored.
 */

@TransactionManagement (TransactionManagementType.BEAN)
@MessageDriven(mappedName = "jms/BladeAnalyticsDistributedQueue", activationConfig = {
		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge"),
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue") })
public class AnalyticsJmsListener implements MessageListener {

	private static final String MDB_MBEAN_QUERY = "com.bea:Type=MessageDrivenEJBRuntime,Name=AnalyticsJmsListener,*";

	private EntityManagerFactory emf;
	private static Logger sipLogger;
	private static volatile boolean databaseDown = false;
	private static Timer healthCheckTimer;

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
			if (sipLogger != null) {
				sipLogger.severe("AnalyticsJmsListener: Failed to create EntityManagerFactory: " + e.getMessage());
			} else {
				System.err.println("AnalyticsJmsListener: Failed to create EntityManagerFactory: " + e.getMessage());
			}
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

		// Lazy init logger if it wasn't available at @PostConstruct time
		if (sipLogger == null) {
			sipLogger = SettingsManager.getSipLogger();
		}

		// Safety net: if a message slips through during the brief window between
		// setting databaseDown and the JMX suspend taking effect, discard it.
		if (databaseDown) {
			logWarning("AnalyticsJmsListener: Database unavailable, discarding message");
			return;
		}

		if (!(message instanceof ObjectMessage)) {
			logWarning("AnalyticsJmsListener: Received non-ObjectMessage, ignoring");
			return;
		}

		// Lazy init EMF if it wasn't available at startup
		if (emf == null) {
			try {
				emf = Persistence.createEntityManagerFactory("BladeAnalytics");
			} catch (Exception e) {
				logSevere("AnalyticsJmsListener: Cannot create EntityManagerFactory: " + e.getMessage(), e);
				reportDatabaseDown();
				return;
			}
		}

		EntityManager em = null;
		try {
			ObjectMessage objectMessage = (ObjectMessage) message;
			Serializable object = objectMessage.getObject();

			em = emf.createEntityManager();
			em.getTransaction().begin();

			if (object instanceof Application) {
				logInfo("AnalyticsJmsListener.onMessage - persisting Application:\n"
						+ safeSerialize(object));
				persistApplication(em, (Application) object);
			} else if (object instanceof Session) {
				logInfo("AnalyticsJmsListener.onMessage - persisting Session:\n"
						+ safeSerialize(object));
				persistSession(em, (Session) object);
			} else if (object instanceof Event) {
				logInfo("AnalyticsJmsListener.onMessage - persisting Event:\n"
						+ safeSerialize(object));
				persistEvent(em, (Event) object);
			} else if (object instanceof SessionKey) {
				logInfo("AnalyticsJmsListener.onMessage - persisting SessionKey:\n"
						+ safeSerialize(object));
				persistSessionKey(em, (SessionKey) object);
			} else {
				logSevere("AnalyticsJmsListener: Unable to persist unknown object type: "
						+ (object == null ? "null" : object.getClass().getName()), null);
			}

			em.getTransaction().commit();

		} catch (Exception ex) {
			// Roll back the EntityManager transaction if active
			if (em != null) {
				try {
					if (em.getTransaction().isActive()) {
						em.getTransaction().rollback();
					}
				} catch (Exception rollbackEx) {
					logSevere("AnalyticsJmsListener: Rollback failed: " + rollbackEx.getMessage(), rollbackEx);
				}
			}

			if (isDatabaseConnectionError(ex)) {
				logSevere("AnalyticsJmsListener: Database connection error: " + ex.getMessage(), ex);
				closeEmf();
				reportDatabaseDown();
			} else {
				// Non-connection error (bad data, constraint violation, etc.)
				// Log it but let the message be consumed to avoid infinite redelivery
				logSevere("AnalyticsJmsListener: Persist error: " + ex.getMessage(), ex);
			}
		} finally {
			if (em != null && em.isOpen()) {
				em.close();
			}
		}
	}

	// ─── Defensive logging — sipLogger may be null if SettingsManager hasn't ────
	// been initialized yet (the SIP servlet half of services/analytics owns it).
	// All logging in onMessage and the persist helpers goes through these so a
	// secondary NPE in the logger never masks the real underlying error.

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
		System.err.println("[AnalyticsJmsListener] SEVERE: " + msg);
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
		System.err.println("[AnalyticsJmsListener] WARNING: " + msg);
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
		System.out.println("[AnalyticsJmsListener] INFO: " + msg);
	}

	private static String safeSerialize(Object o) {
		try {
			return Logger.serializeObject(o);
		} catch (Throwable t) {
			return "<serializeObject failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
					+ "; type=" + (o == null ? "null" : o.getClass().getName()) + ">";
		}
	}

	private void closeEmf() {
		if (emf != null && emf.isOpen()) {
			try {
				emf.close();
			} catch (Exception e) {
				logWarning("AnalyticsJmsListener: Error closing EntityManagerFactory: " + e.getMessage());
			}
		}
		emf = null;
	}

	private void reportDatabaseDown() {
		synchronized (AnalyticsJmsListener.class) {
			if (!databaseDown) {
				databaseDown = true;
				logSevere("AnalyticsJmsListener: Database failure detected, suspending message delivery", null);
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
			logWarning("AnalyticsJmsListener: Started health check timer (" + (healthCheckInterval) + " interval)");
		}
	}

	@Timeout
	public void checkDatabaseHealth(Timer timer) {
		synchronized (AnalyticsJmsListener.class) {
			if (testDatabaseConnection()) {
				databaseDown = false;
				healthCheckTimer = null;
				timer.cancel();
				resumeMessageDelivery();
				logWarning("AnalyticsJmsListener: Database connection restored, resumed message delivery");
			}
		}
	}

	/**
	 * Suspends JMS message delivery for this MDB via WebLogic JMX. Messages
	 * will accumulate in the persistent store until delivery is resumed.
	 */
	private void suspendMessageDelivery() {
		try {
			MBeanServer mbs = (MBeanServer) new InitialContext().lookup("java:comp/env/jmx/runtime");
			Set<ObjectName> mbeans = mbs.queryNames(new ObjectName(MDB_MBEAN_QUERY), null);
			for (ObjectName name : mbeans) {
				mbs.invoke(name, "suspend", null, null);
				logWarning("AnalyticsJmsListener: JMS message delivery suspended via JMX (" + name + ")");
			}
		} catch (Exception e) {
			logSevere("AnalyticsJmsListener: Failed to suspend message delivery via JMX: " + e.getMessage(), e);
		}
	}

	/**
	 * Resumes JMS message delivery for this MDB via WebLogic JMX.
	 */
	private void resumeMessageDelivery() {
		try {
			MBeanServer mbs = (MBeanServer) new InitialContext().lookup("java:comp/env/jmx/runtime");
			Set<ObjectName> mbeans = mbs.queryNames(new ObjectName(MDB_MBEAN_QUERY), null);
			for (ObjectName name : mbeans) {
				mbs.invoke(name, "resume", null, null);
				logWarning("AnalyticsJmsListener: JMS message delivery resumed via JMX (" + name + ")");
			}
		} catch (Exception e) {
			logSevere("AnalyticsJmsListener: Failed to resume message delivery via JMX: " + e.getMessage(), e);
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
			logSevere("AnalyticsJmsListener: Connection test failed: " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Walks the exception cause chain to determine if the root cause is a database
	 * connectivity issue (as opposed to a data/constraint error).
	 */
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

	private void persistApplication(EntityManager em, Application application) {
		em.merge(application);
		logInfo("AnalyticsJmsListener: Persisted Application id=" + application.getId());
	}

	private void persistSession(EntityManager em, Session session) {
		em.merge(session);
		logInfo("AnalyticsJmsListener: Persisted Session id=" + session.getId());
	}

	private void persistSessionKey(EntityManager em, SessionKey sk) {
		// Composite PK (session_id, name, value) — em.merge is idempotent on retransmits.
		em.merge(sk);
		logInfo("AnalyticsJmsListener: Persisted SessionKey session_id="
				+ sk.getId().getSessionId() + " name=" + sk.getId().getName()
				+ " value=" + sk.getId().getValue());
	}

	private void persistEvent(EntityManager em, Event event) {
		// Translate the wire-side event name to an event_type_id.
		event.setEventTypeId(lookupEventTypeId(em, event.getName()));

		// Detach the wire-side attribute map so JPA doesn't try to cascade
		// (Event.attributes is @Transient anyway, but be explicit).
		Map<String, Attribute> incoming = event.getAttributes();
		event.setAttributes(new java.util.HashMap<>());

		// Persist the event itself; flush so the IDENTITY-generated id is
		// available for the attribute rows.
		em.persist(event);
		em.flush();

		long eventId = event.getId();
		for (Attribute attr : incoming.values()) {
			short nameId = lookupAttributeNameId(em, attr.getName());
			attr.setId(new AttributePK(eventId, nameId));
			em.persist(attr);
		}

		logInfo("AnalyticsJmsListener: Persisted Event id=" + eventId
				+ " event_type_id=" + event.getEventTypeId()
				+ " attribute_count=" + incoming.size());
	}

	// ─── Lookup caches for normalized name → id ─────────────────────────

	private final ConcurrentMap<String, Short> eventTypeCache = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Short> attributeNameCache = new ConcurrentHashMap<>();

	private short lookupEventTypeId(EntityManager em, String name) {
		Short id = eventTypeCache.get(name);
		if (id != null) {
			return id;
		}
		synchronized (eventTypeCache) {
			id = eventTypeCache.get(name);
			if (id != null) {
				return id;
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
			eventTypeCache.put(name, et.getId());
			return et.getId();
		}
	}

	private short lookupAttributeNameId(EntityManager em, String name) {
		Short id = attributeNameCache.get(name);
		if (id != null) {
			return id;
		}
		synchronized (attributeNameCache) {
			id = attributeNameCache.get(name);
			if (id != null) {
				return id;
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
			attributeNameCache.put(name, an.getId());
			return an.getId();
		}
	}

}
