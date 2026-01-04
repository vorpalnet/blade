package org.vorpal.blade.services.analytics.jms;

import java.util.Enumeration;
import java.util.Hashtable;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueReceiver;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/**
 * This example shows how to establish a connection to and receive messages from
 * a JMS queue. The classes in this package operate on the same JMS queue. Run
 * the classes together to witness messages being sent and received, and to
 * browse the queue for messages. This class is used to receive and remove
 * messages from the queue.
 *
 * @author Copyright (c) 2013-2025 by Vorpal, Inc. All Rights Reserved.
 */
@MessageDriven(mappedName = "jms/TestJMSQueue", activationConfig = {
		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge"),
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue") })
public class QueueReceive extends ClientCallflow implements MessageListener {
	// Defines the JNDI context factory.
	public final static String JNDI_FACTORY = "weblogic.jndi.WLInitialContextFactory";
	// Defines the JMS connection factory for the queue.
	public final static String JMS_FACTORY = "jms/TestConnectionFactory";
	// Defines the queue.
	public final static String QUEUE = "jms/TestJMSQueue";
	private QueueConnectionFactory qconFactory;
	private QueueConnection qcon;
	private QueueSession qsession;
	private QueueReceiver qreceiver;
	private Queue queue;
	private boolean quit = false;

	public QueueReceive() {
		// default constructor
	}

	/**
	 * Message listener interface.
	 * 
	 * @param msg message
	 */
	@Override
//	public void onMessage(ObjectMessage msg) {
	public void onMessage(Message msg) {

		MapMessage mapMessage;
//		EntityManagerFactory emf = null;
//		EntityManager em = null;

//		System.out.println("onMessage msg instanceof Message=" + (msg instanceof Message) //
//				+ ", TextMessage=" + (msg instanceof TextMessage) //
//				+ ", ObjectMessage=" + (msg instanceof ObjectMessage) //
//				+ ", MapMessage=" + (msg instanceof MapMessage) //
//				);

		try {
//			EventDetailRecord eventRecord = (EventDetailRecord) ((ObjectMessage) msg).getObject();

			if (msg instanceof MapMessage) {
				mapMessage = (MapMessage) msg;

				Enumeration<String> mapNames = mapMessage.getMapNames();

				sipLogger.info("Property names in MapMessage:");
				while (mapNames.hasMoreElements()) {
					String propertyName = mapNames.nextElement();
					sipLogger.info(propertyName + "=" + mapMessage.getString(propertyName));
				}

				// now insert it into the tables

//				sipLogger.warning("Persistence.createEntityManagerFactory...");
//				emf = Persistence.createEntityManagerFactory("BladeCDR"); // Replace with your persistence unit name
//				sipLogger.warning("emf.createEntityManager...");
//				em = emf.createEntityManager();
//
//				sipLogger.warning("em.getTransaction().begin()...");
//				em.getTransaction().begin(); // Start a transaction
//
//				String id = mapMessage.getString("X-Vorpal-Session");
//				String timestamp = mapMessage.getString("X-Vorpal-Timestamp");
//
//				CdrSessionPK cdrSessionPK = new CdrSessionPK();
//				cdrSessionPK.setId(id);
//				cdrSessionPK.setStart(new Date(Long.parseLong(timestamp, 16)));
//
//				CdrSession cdrSession = new CdrSession();
//				cdrSession.setId(cdrSessionPK);
//
//				cdrSession.setCluster(mapMessage.getString("cluster"));
//				cdrSession.setDomain(mapMessage.getString("domain"));
//
//				// Persist the Product object (insert into the database)
//				sipLogger.warning("em.persist...");
//				em.persist(cdrSession);
//
////				sipLogger.warning("em.getTransaction().commit()...");
////				em.getTransaction().commit(); // Commit the transaction

			}

		} catch (JMSException ex) {
			sipLogger.severe(ex);
		} catch (Exception e) {
			sipLogger.severe(e);
//			if (em.getTransaction().isActive()) {
//				em.getTransaction().rollback(); // Rollback in case of error
//			}
			e.printStackTrace();
		} 
		
		finally {

//			if (em != null) {
//				em.close(); // Close the EntityManager
//			}
//
//			if (emf != null) {
//				emf.close(); // Close the EntityManagerFactory
//			}
			
			
		}

	}

	/**
	 * Creates all the necessary objects for receiving messages from a JMS queue.
	 *
	 * @param ctx       JNDI initial context
	 * @param queueName name of queue
	 * @exception NamingException if operation cannot be performed
	 * @exception JMSException    if JMS fails to initialize due to internal error
	 */
	public void init(Context ctx, String queueName) throws NamingException, JMSException {
		qconFactory = (QueueConnectionFactory) ctx.lookup(JMS_FACTORY);
		qcon = qconFactory.createQueueConnection();
		qsession = qcon.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
		queue = (Queue) ctx.lookup(queueName);
		qreceiver = qsession.createReceiver(queue);
		qreceiver.setMessageListener(this);
		qcon.start();
	}

	/**
	 * Closes JMS objects.
	 * 
	 * @exception JMSException if JMS fails to close objects due to internal error
	 */
	public void close() throws JMSException {
		qreceiver.close();
		qsession.close();
		qcon.close();
	}

	/**
	 * main() method.
	 *
	 * @param args WebLogic Server URL
	 * @exception Exception if execution fails
	 */
	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.out.println("Usage: java examples.jms.queue.QueueReceive WebLogicURL");
			return;
		}
		InitialContext ic = getInitialContext(args[0]);
		QueueReceive qr = new QueueReceive();
		qr.init(ic, QUEUE);
		System.out.println("JMS Ready To Receive Messages (To quit, send a \"quit\" message).");
		// Wait until a "quit" message has been received.
		synchronized (qr) {
			while (!qr.quit) {
				try {
					qr.wait();
				} catch (InterruptedException ie) {
				}
			}
		}
		qr.close();
	}

	private static InitialContext getInitialContext(String url) throws NamingException {
		Hashtable<String, String> env = new Hashtable<>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, JNDI_FACTORY);
		env.put(Context.PROVIDER_URL, url);
		return new InitialContext(env);
	}

}