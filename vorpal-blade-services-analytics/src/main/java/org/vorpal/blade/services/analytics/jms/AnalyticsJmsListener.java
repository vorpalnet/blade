package org.vorpal.blade.services.analytics.jms;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

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
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.analytics.Application;
import org.vorpal.blade.framework.v2.analytics.Event;
import org.vorpal.blade.framework.v2.analytics.Session;
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
			sipLogger.severe("AnalyticsJmsListener: Failed to create EntityManagerFactory: " + e.getMessage());
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

		// Safety net: if a message slips through during the brief window between
		// setting databaseDown and the JMX suspend taking effect, roll it back.
		if (databaseDown) {
			sipLogger.warning("AnalyticsJmsListener: Database unavailable, rolling back message");
			mdbContext.setRollbackOnly();
			return;
		}

		if (!(message instanceof ObjectMessage)) {
			sipLogger.warning("AnalyticsJmsListener: Received non-ObjectMessage, ignoring");
			return;
		}

		// Lazy init EMF if it wasn't available at startup
		if (emf == null) {
			try {
				emf = Persistence.createEntityManagerFactory("BladeAnalytics");
			} catch (Exception e) {
				sipLogger.severe("AnalyticsJmsListener: Cannot create EntityManagerFactory: " + e.getMessage());
				reportDatabaseDown();
				mdbContext.setRollbackOnly();
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
				sipLogger.info("AnalyticsJmsListener.onMessage - persisting Application:\n"
						+ Logger.serializeObject((Application) object));
				persistApplication(em, (Application) object);
			} else if (object instanceof Session) {
				sipLogger.info("AnalyticsJmsListener.onMessage - persisting Session:\n"
						+ Logger.serializeObject((Session) object));
				persistSession(em, (Session) object);
			} else if (object instanceof Event) {
				sipLogger.info("AnalyticsJmsListener.onMessage - persisting Event:\n"
						+ Logger.serializeObject((Event) object));
				persistEvent(em, (Event) object);
			} else {
				sipLogger.severe(
						"AnalyticsJmsListener: Unable to persist unknown object type: " + object.getClass().getName());
				sipLogger.logConfiguration(object);
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
					sipLogger.severe("AnalyticsJmsListener: Rollback failed: " + rollbackEx.getMessage());
				}
			}

			if (isDatabaseConnectionError(ex)) {
				sipLogger.severe("AnalyticsJmsListener: Database connection error: " + ex.getMessage());
				closeEmf();
				reportDatabaseDown();
				mdbContext.setRollbackOnly();
			} else {
				// Non-connection error (bad data, constraint violation, etc.)
				// Log it but let the message be consumed to avoid infinite redelivery
				sipLogger.severe("AnalyticsJmsListener: Persist error: " + ex.getMessage());
				sipLogger.severe(ex);
			}
		} finally {
			if (em != null && em.isOpen()) {
				em.close();
			}
		}
	}

	private void closeEmf() {
		if (emf != null && emf.isOpen()) {
			try {
				emf.close();
			} catch (Exception e) {
				sipLogger.warning("AnalyticsJmsListener: Error closing EntityManagerFactory: " + e.getMessage());
			}
		}
		emf = null;
	}

	private void reportDatabaseDown() {
		synchronized (AnalyticsJmsListener.class) {
			if (!databaseDown) {
				databaseDown = true;
				sipLogger.severe("AnalyticsJmsListener: Database failure detected, suspending message delivery");
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
			sipLogger
					.warning("AnalyticsJmsListener: Started health check timer (" + (healthCheckInterval) + " interval)");
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
				sipLogger.warning("AnalyticsJmsListener: Database connection restored, resumed message delivery");
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
				sipLogger.warning("AnalyticsJmsListener: JMS message delivery suspended via JMX (" + name + ")");
			}
		} catch (Exception e) {
			sipLogger.severe("AnalyticsJmsListener: Failed to suspend message delivery via JMX: " + e.getMessage());
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
				sipLogger.warning("AnalyticsJmsListener: JMS message delivery resumed via JMX (" + name + ")");
			}
		} catch (Exception e) {
			sipLogger.severe("AnalyticsJmsListener: Failed to resume message delivery via JMX: " + e.getMessage());
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
			sipLogger.severe("AnalyticsJmsListener: Connection test failed: " + e.getMessage());
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
		em.persist(application);
		sipLogger.info("AnalyticsJmsListener: Persisted Application id=" + application.getId());
	}

	private void persistSession(EntityManager em, Session session) {
		em.persist(session);
		sipLogger.info("AnalyticsJmsListener: Persisted Session id=" + session.getId());
	}

	private void persistEvent(EntityManager em, Event event) {
		// Use the custom persistEvent method to handle AttributePK.eventId update
		event.persistEvent(em);
		sipLogger.info("AnalyticsJmsListener: Persisted Event id=" + event.getId());
	}

}
