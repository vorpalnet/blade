package org.vorpal.blade.services.analytics.jms;

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

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.services.analytics.jpa.Application;

/**
 * Utility class for publishing JPA entities to a JMS queue as ObjectMessages.
 */
public class JmsPublisher {

	public static final String JMS_FACTORY = "jms/TestConnectionFactory";
	public static final String QUEUE = "jms/TestJMSQueue";

	private InitialContext ctx;
	private QueueConnectionFactory qconFactory;
	private QueueConnection qcon;
	private QueueSession qsession;
	private Queue queue;
	private QueueSender qsender;
	private Logger sipLogger;

	/**
	 * Initialize the JMS connection and session.
	 *
	 * @throws NamingException if JNDI lookup fails
	 * @throws JMSException    if JMS initialization fails
	 */
	public void init() throws NamingException, JMSException {
		sipLogger = SettingsManager.getSipLogger();
		
		sipLogger.finer("JmsPublisher.init");

		ctx = new InitialContext();
		qconFactory = (QueueConnectionFactory) ctx.lookup(JMS_FACTORY);
		qcon = qconFactory.createQueueConnection();
		qsession = qcon.createQueueSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
		queue = (Queue) ctx.lookup(QUEUE);
		qsender = qsession.createSender(queue);
		qcon.start();
	}

	private Application application = null;

	@SuppressWarnings("rawtypes")
	public void applicationStart() {
		sipLogger.finer("JmsPublisher.applicationStart");

		try {

			if (application == null) {

				application = new Application();
				application.setId(SettingsManager.getAppInstanceId());
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
		sipLogger.finer("JmsPublisher.applicationStop");

		try {
			Application application = new Application();
			application.setId(SettingsManager.getAppInstanceId());
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
		sipLogger.finer("JmsPublisher.send");

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
		sipLogger.finer("JmsPublisher.send(Serializable object, String type)");

		ObjectMessage message = qsession.createObjectMessage();
		message.setObject(object);
		message.setStringProperty("type", type);
		qsender.send(message);
	}

	/**
	 * Close JMS resources.
	 */
	public void close() {
		sipLogger.finer("JmsPublisher.close");

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
