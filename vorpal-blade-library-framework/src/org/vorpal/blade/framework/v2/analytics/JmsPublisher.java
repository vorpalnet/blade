package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.sql.Date;
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
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * Utility class for publishing JPA entities to a JMS queue as ObjectMessages.
 */
// jwm - come back to this later
// @WebListener
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
//		sipLogger.finer("JmsPublisher.applicationStop");

		try {
			System.out.println("JmsPublisher application.setId=" + Analytics.getAppInstanceId());
			application.setId(Analytics.getAppInstanceId());
			System.out.println("JmsPublisher application.setDestroyed=" + Date.from(Instant.now()));
			application.setDestroyed(Date.from(Instant.now()));
			ObjectMessage message = qsession.createObjectMessage();
			message.setObject(application);
			qsender.send(message);
		} catch (Exception ex) {
			ex.printStackTrace();
		}

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

	@Override
	public void contextInitialized(ServletContextEvent event) {
		// Code to run when the web application is started
		Callflow.getSipLogger().warning("JmsPublisher.contextInitialized - Analytics JmsPublisher starting...");

		try {

			if (SettingsManager.getAnalytics() != null && Analytics.jmsPublisher == null) {
				Analytics.jmsPublisher = new JmsPublisher(SettingsManager.getAnalytics().getJmsFactory(),
						SettingsManager.getAnalytics().getJmsQueue());
				Analytics.jmsPublisher.init();
			}

		} catch (Exception ex) {
			ex.printStackTrace();
		}

	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		// Code to run when the web application is shutting down
		Callflow.getSipLogger().warning("Analytics JmsPublisher stopped.");

		if (Analytics.jmsPublisher != null) {
			Analytics.jmsPublisher.applicationStop();
			Analytics.jmsPublisher.close();
		}

	}

}
