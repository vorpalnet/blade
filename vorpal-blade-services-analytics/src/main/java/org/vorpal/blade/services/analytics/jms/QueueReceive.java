package org.vorpal.blade.services.analytics.jms;

import java.io.Serializable;
import java.util.Hashtable;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueReceiver;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v2.logging.Logger;

/**
 * This example shows how to establish a connection to and receive messages from
 * a JMS queue. The classes in this package operate on the same JMS queue. Run
 * the classes together to witness messages being sent and received, and to
 * browse the queue for messages. This class is used to receive and remove
 * messages from the queue.
 *
 * @author Copyright (c) 2013-2025 by Vorpal, Inc. All Rights Reserved.
 */
//@MessageDriven(mappedName = "jms/TestJMSQueue", activationConfig = {
//		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge"),
//		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue") })
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
	private static Logger sipLogger;

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
	public void onMessage(Message message) {

	

		    if (message instanceof ObjectMessage) {
		        try {
		            ObjectMessage objectMessage = (ObjectMessage) message;
		            
		            // Use the getObject() method
		            Serializable receivedObject = objectMessage.getObject();
		            
		    		System.out.println("QueueReceive.onMessage - receivedObject=" + receivedObject.getClass().getName());
		            System.out.println(Logger.serializeObject(receivedObject));
		            
		            
		            
		            
//		            // You can then cast the Serializable object to its specific class
//		            // (e.g., if you know it's a 'MyCustomObject' class)
//		            // Note: The class must be present on the consumer's classpath and serializable.
//		            if (receivedObject instanceof MyCustomObject) {
//		                MyCustomObject myObject = (MyCustomObject) receivedObject;
//		                // Now you can call methods on your object
//		                myObject.anyMethodDefinedForTheObject();
//		            } else {
//		                System.out.println("Received unexpected object type.");
//		            }

		        } catch (JMSException e) {
		            e.printStackTrace();
		            // Handle JMS errors (e.g., deserialization failure, internal provider error)
		        }
		    } else {
		        // Handle other message types if necessary
		        System.out.println("Received non-ObjectMessage.");
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

		System.out.println("QueueReceive.init");

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
		System.out.println("QueueReceive.close");

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
		System.out.println("QueueReceive.getInitialContext");

		Hashtable<String, String> env = new Hashtable<>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, JNDI_FACTORY);
		env.put(Context.PROVIDER_URL, url);
		return new InitialContext(env);
	}

}