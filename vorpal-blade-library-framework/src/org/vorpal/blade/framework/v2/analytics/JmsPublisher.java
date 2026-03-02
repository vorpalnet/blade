package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;

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
 */
public class JmsPublisher implements ServletContextListener {

	private String jmsFactory;
	private String jmsQueue;

	private InitialContext ctx;
	private QueueConnectionFactory qconFactory;
	private QueueConnection qcon;
	private QueueSession qsession;
	private Queue queue;
	private QueueSender qsender;
//	private Logger sipLogger;

	public JmsPublisher() {
//		Callflow.getSipLogger().warning("JmsPublisher.constructor");

	}

	public JmsPublisher(String jmsFactory, String jmsQueue) {

//		Callflow.getSipLogger()
//				.warning("JmsPublisher.constructor - jsmFactory=" + jmsFactory + ", jmsQueue=" + jmsQueue);

		this.jmsFactory = jmsFactory;
		this.jmsQueue = jmsQueue;
	}

	/**
	 * Initialize the JMS connection and session.
	 *
	 * @throws NamingException if JNDI lookup fails
	 * @throws JMSException    if JMS initialization fails
	 */
	public void init() throws NamingException, JMSException {
		SettingsManager.getSipLogger().info("JmsPublisher.init - jmsFactory=" + jmsFactory + ", jmsQueue=" + jmsQueue);

		ctx = new InitialContext();

		qconFactory = (QueueConnectionFactory) ctx.lookup(jmsFactory);
		qcon = qconFactory.createQueueConnection();
		qsession = qcon.createQueueSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
		queue = (Queue) ctx.lookup(jmsQueue);
		qsender = qsession.createSender(queue);
		qcon.start();

		SettingsManager.getSipLogger().info("JmsPublisher.init - qconFactory=" + qconFactory + ", qcon=" + qcon
				+ ", qsession=" + qsession + ", queue=" + queue + ", qsender=" + qsender);

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
//		sipLogger.finer("JmsPublisher.applicationStart");

		try {

			if (application == null) {

				application = new Application();

				application.setId(Analytics.getAppInstanceId());

				application.setDomain(SettingsManager.getDomainName());

				application.setHost(SettingsManager.getHostname());

				application.setName(SettingsManager.getApplicationName());

				application.setServer(SettingsManager.getServerName());

				application.setCreated(Date.from(Instant.now()));

				ObjectMessage message = qsession.createObjectMessage();
				message.setObject(application);
				qsender.send(message);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

	}

	@SuppressWarnings("rawtypes")
	public void applicationStop() {
		Logger sipLogger = Callflow.getSipLogger();

		try {
			sipLogger.warning("JmsPublisher.applicationStop - application.setId=" + Analytics.getAppInstanceId());
			application.setId(Analytics.getAppInstanceId());
			System.out.println("JmsPublisher.applicationStop - application.setDestroyed=" + Date.from(Instant.now()));
			application.setDestroyed(Date.from(Instant.now()));
			ObjectMessage message = qsession.createObjectMessage();
			message.setObject(application);
			qsender.send(message);
		} catch (Exception ex) {
			sipLogger.severe(ex);
		}

	}

	public Session sessionStart(SipServletMessage msg) {
		Session session = null;
		Logger sipLogger = Callflow.getSipLogger();
		try {
			sipLogger.warning(msg, "JmsPublisher.sessionStart... jmsPublisher="+Analytics.jmsPublisher+", isLoggable="+sipLogger.isLoggable(sipLogger.getAnalyticsLoggingLevel()));
			if (Analytics.jmsPublisher != null || sipLogger.isLoggable(sipLogger.getAnalyticsLoggingLevel())) {
				session = Analytics.createSession(msg);
				sipLogger.warning(msg, "JmsPublisher.sessionStart... session="+session);
				if (session != null && Analytics.jmsPublisher != null) {
					ObjectMessage message = qsession.createObjectMessage();
					message.setObject(session);
					sipLogger.warning(msg, "JmsPublisher.sessionStart... sending session");
					qsender.send(message);
					sipLogger.warning(msg, "JmsPublisher.sessionStart... session sent");
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
					ObjectMessage message = qsession.createObjectMessage();
					message.setObject(session);
					qsender.send(message);
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
//		sipLogger.finer("JmsPublisher.send");

		SettingsManager.sipLogger.warning("JmsPublisher.send - is qsession null? " + (qsession == null));

		ObjectMessage message = qsession.createObjectMessage();
		message.setObject(object);

		qsender.send(message);
		Callflow.getSipLogger().warning("JmsPublisher.send - Sending " + object.getClass().getSimpleName() + ":\n"
				+ Logger.serializeObject(object));
	}

	/**
	 * Send a Serializable object with a type hint for the receiver.
	 *
	 * @param object the object to send
	 * @param type   the class name hint for deserialization
	 * @throws JMSException if sending fails
	 */
	public void send(Serializable object, String type) throws JMSException {
//		sipLogger.finer("JmsPublisher.send(Serializable object, String type)");

		ObjectMessage message = qsession.createObjectMessage();
		message.setObject(object);
		message.setStringProperty("type", type);
		qsender.send(message);
	}

	/**
	 * Close JMS resources.
	 */
	public void close() {
//		sipLogger.finer("JmsPublisher.close");

		try {
			if (qsender != null)
				qsender.close();
			if (qsession != null)
				qsession.close();
			if (qcon != null)
				qcon.close();
		} catch (JMSException e) {
			// log but don't throw
			e.printStackTrace();
		}
	}

}
