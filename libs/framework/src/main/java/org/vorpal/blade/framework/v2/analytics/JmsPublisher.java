package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.jms.JMSException;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContextListener;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/**
 * Utility class for publishing JPA entities to a JMS queue as ObjectMessages.
 *
 * The shared {@link QueueConnection} is created once in {@link #init()} and
 * reused across threads (Connection objects are thread-safe per the JMS spec).
 * A separate {@link QueueSession} + {@link QueueSender} pair is created
 * lazily per calling thread, because WebLogic JMS sessions are not thread-safe:
 * "A session and its message producers and consumers can only be accessed by
 * one thread at a time" (WLS JMS Programming Guide, Session Guidelines).
 */
public class JmsPublisher implements ServletContextListener {

	private String jmsFactory;
	private String jmsQueue;

	private InitialContext ctx;
	private QueueConnectionFactory qconFactory;
	private QueueConnection qcon;
	private Queue queue;

	private final ConcurrentMap<Thread, ThreadResources> threadResources = new ConcurrentHashMap<>();

	private static final class ThreadResources {
		final QueueSession session;
		final QueueSender sender;

		ThreadResources(QueueSession session, QueueSender sender) {
			this.session = session;
			this.sender = sender;
		}
	}

	public JmsPublisher() {
	}

	public JmsPublisher(String jmsFactory, String jmsQueue) {
		this.jmsFactory = jmsFactory;
		this.jmsQueue = jmsQueue;
	}

	/**
	 * Initialize the shared JMS connection and queue handle. Per-thread
	 * sessions and senders are created lazily on first use.
	 *
	 * @throws NamingException if JNDI lookup fails
	 * @throws JMSException    if JMS initialization fails
	 */
	public void init() throws NamingException, JMSException {
		ctx = new InitialContext();
		qconFactory = (QueueConnectionFactory) ctx.lookup(jmsFactory);
		qcon = qconFactory.createQueueConnection();
		queue = (Queue) ctx.lookup(jmsQueue);
		qcon.start();
	}

	private ThreadResources getThreadResources() throws JMSException {
		Thread me = Thread.currentThread();
		ThreadResources tr = threadResources.get(me);
		if (tr == null) {
			QueueSession session = qcon.createQueueSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
			QueueSender sender = session.createSender(queue);
			tr = new ThreadResources(session, sender);
			ThreadResources existing = threadResources.putIfAbsent(me, tr);
			if (existing != null) {
				sender.close();
				session.close();
				tr = existing;
			}
		}
		return tr;
	}

	public String getJmsFactory() {
		return jmsFactory;
	}

	public JmsPublisher setJmsFactory(String jmsFactory) {
		this.jmsFactory = jmsFactory;
		return this;
	}

	public String getJmsQueue() {
		return jmsQueue;
	}

	public JmsPublisher setJmsQueue(String jmsQueue) {
		this.jmsQueue = jmsQueue;
		return this;
	}

	private static Application application = null;

	@SuppressWarnings("rawtypes")
	public void applicationStart() {
		try {

			if (application == null) {
				application = new Application();
				application.setId(Analytics.getAppInstanceId());
				application.setDomain(SettingsManager.getDomainName());
				application.setHost(SettingsManager.getHostname());
				application.setName(SettingsManager.getApplicationName());
				application.setServer(SettingsManager.getServerName());
				application.setCreated(Date.from(Instant.now()));

				ThreadResources tr = getThreadResources();
				ObjectMessage message = tr.session.createObjectMessage();
				message.setObject(application);
				tr.sender.send(message);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	@SuppressWarnings("rawtypes")
	public void applicationStop() {
		Logger sipLogger = Callflow.getSipLogger();
		try {
			application.setId(Analytics.getAppInstanceId());
			application.setDestroyed(Date.from(Instant.now()));
			if (Analytics.jmsPublisher != null || sipLogger.isLoggable(sipLogger.getAnalyticsLoggingLevel())) {
				ThreadResources tr = getThreadResources();
				ObjectMessage message = tr.session.createObjectMessage();
				message.setObject(application);
				tr.sender.send(message);
			}
		} catch (Exception ex) {
			Callflow.getSipLogger().severe(ex);
		}

	}

	public Session sessionStart(SipServletMessage msg) {
		Session session = null;
		Logger sipLogger = Callflow.getSipLogger();
		try {
			if (Analytics.jmsPublisher != null || sipLogger.isLoggable(sipLogger.getAnalyticsLoggingLevel())) {
				session = Analytics.createSession(msg);
				if (session != null && Analytics.jmsPublisher != null) {
					ThreadResources tr = getThreadResources();
					ObjectMessage message = tr.session.createObjectMessage();
					message.setObject(session);
					tr.sender.send(message);
				}
			}
		} catch (Exception ex) {
			sipLogger.severe(ex);
		}

		return session;
	}

	public Session sessionStop(SipServletMessage msg) {
		Session session = null;
		Logger sipLogger = Callflow.getSipLogger();
		try {

			if (Analytics.jmsPublisher != null || sipLogger.isLoggable(sipLogger.getAnalyticsLoggingLevel())) {
				session = Analytics.createSession(msg);
				session.setDestroyed(Timestamp.from(Instant.now()));

				if (session != null && Analytics.jmsPublisher != null) {
					ThreadResources tr = getThreadResources();
					ObjectMessage message = tr.session.createObjectMessage();
					message.setObject(session);
					tr.sender.send(message);
				}

			}

		} catch (Exception ex) {
			sipLogger.severe(ex);
		}

		return session;
	}

	/**
	 * Send a Serializable object (typically a JPA entity) to the JMS queue.
	 *
	 * @param object the object to send
	 * @throws JMSException if sending fails
	 */
	public void send(Serializable object) throws JMSException {
		ThreadResources tr = getThreadResources();
		ObjectMessage message = tr.session.createObjectMessage();
		message.setObject(object);
		tr.sender.send(message);
	}

	/**
	 * Send a Serializable object with a type hint for the receiver.
	 *
	 * @param object the object to send
	 * @param type   the class name hint for deserialization
	 * @throws JMSException if sending fails
	 */
	public void send(Serializable object, String type) throws JMSException {
		ThreadResources tr = getThreadResources();
		ObjectMessage message = tr.session.createObjectMessage();
		message.setObject(object);
		message.setStringProperty("type", type);
		tr.sender.send(message);
	}

	/**
	 * Close all per-thread JMS sessions/senders and the shared connection.
	 */
	public void close() {
		for (ThreadResources tr : threadResources.values()) {
			try {
				if (tr.sender != null)
					tr.sender.close();
			} catch (JMSException e) {
				e.printStackTrace();
			}
			try {
				if (tr.session != null)
					tr.session.close();
			} catch (JMSException e) {
				e.printStackTrace();
			}
		}
		threadResources.clear();

		try {
			if (qcon != null)
				qcon.close();
		} catch (JMSException e) {
			e.printStackTrace();
		}
	}

}
