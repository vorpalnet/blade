package org.vorpal.blade.services.analytics.jms;

import java.io.Serializable;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.vorpal.blade.services.analytics.jpa.Application;
import org.vorpal.blade.services.analytics.jpa.Event;
import org.vorpal.blade.services.analytics.jpa.Session;

/**
 * Message-driven bean that receives JPA entities via JMS ObjectMessages and
 * persists them to the database.
 */
@MessageDriven(mappedName = "jms/TestJMSQueue", activationConfig = {
		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge"),
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue") })
public class AnalyticsJmsListener implements MessageListener {

	private EntityManagerFactory emf;

	@PostConstruct
	public void init() {
		try {
			emf = Persistence.createEntityManagerFactory("BladeCDR");
		} catch (Exception e) {
			System.err.println("AnalyticsJmsListener: Failed to create EntityManagerFactory: " + e.getMessage());
			e.printStackTrace();
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
		if (!(message instanceof ObjectMessage)) {
			System.err.println("AnalyticsJmsListener: Received non-ObjectMessage, ignoring");
			return;
		}

		EntityManager em = null;
		try {
			ObjectMessage objectMessage = (ObjectMessage) message;
			Serializable object = objectMessage.getObject();

			em = emf.createEntityManager();

			if (object instanceof Application) {
				persistApplication(em, (Application) object);
			} else if (object instanceof Session) {
				persistSession(em, (Session) object);
			} else if (object instanceof Event) {
				persistEvent(em, (Event) object);
			} else {
				System.err.println("AnalyticsJmsListener: Unknown object type: " + object.getClass().getName());
			}

		} catch (JMSException e) {
			System.err.println("AnalyticsJmsListener: JMS error: " + e.getMessage());
			e.printStackTrace();
		} catch (Exception e) {
			System.err.println("AnalyticsJmsListener: Persistence error: " + e.getMessage());
			e.printStackTrace();
		} finally {
			if (em != null && em.isOpen()) {
				em.close();
			}
		}
	}

	private void persistApplication(EntityManager em, Application application) {
		em.getTransaction().begin();
		em.persist(application);
		em.getTransaction().commit();
		System.out.println("AnalyticsJmsListener: Persisted Application id=" + application.getId());
	}

	private void persistSession(EntityManager em, Session session) {
		em.getTransaction().begin();
		em.persist(session);
		em.getTransaction().commit();
		System.out.println("AnalyticsJmsListener: Persisted Session id=" + session.getId());
	}

	private void persistEvent(EntityManager em, Event event) {
		// Use the custom persistEvent method to handle AttributePK.eventId update
		event.persistEvent(em);
		System.out.println("AnalyticsJmsListener: Persisted Event id=" + event.getId());
	}

}
