package org.vorpal.blade.services.analytics.jms;

import java.io.Serializable;

import javax.jms.JMSException;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.naming.InitialContext;
import javax.naming.NamingException;

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

	/**
	 * Initialize the JMS connection and session.
	 *
	 * @throws NamingException if JNDI lookup fails
	 * @throws JMSException    if JMS initialization fails
	 */
	public void init() throws NamingException, JMSException {
		ctx = new InitialContext();
		qconFactory = (QueueConnectionFactory) ctx.lookup(JMS_FACTORY);
		qcon = qconFactory.createQueueConnection();
		qsession = qcon.createQueueSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
		queue = (Queue) ctx.lookup(QUEUE);
		qsender = qsession.createSender(queue);
		qcon.start();
	}

	/**
	 * Send a Serializable object (typically a JPA entity) to the JMS queue.
	 *
	 * @param object the object to send
	 * @throws JMSException if sending fails
	 */
	public void send(Serializable object) throws JMSException {
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
		ObjectMessage message = qsession.createObjectMessage();
		message.setObject(object);
		message.setStringProperty("type", type);
		qsender.send(message);
	}

	/**
	 * Close JMS resources.
	 */
	public void close() {
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
