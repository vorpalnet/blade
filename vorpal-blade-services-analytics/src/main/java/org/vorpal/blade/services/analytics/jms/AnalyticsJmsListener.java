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

import org.vorpal.blade.framework.v2.analytics.Application;
import org.vorpal.blade.framework.v2.analytics.Event;
import org.vorpal.blade.framework.v2.analytics.Session;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/**
 * Message-driven bean that receives JPA entities via JMS ObjectMessages and
 * persists them to the database.
 */
@MessageDriven(mappedName = "jms/TestJMSQueue", activationConfig = {
		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge"),
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue") })
public class AnalyticsJmsListener implements MessageListener {

	private EntityManagerFactory emf;
	private static Logger sipLogger;

	@PostConstruct
	public void init() {
		sipLogger = SettingsManager.getSipLogger();
		try {
			emf = Persistence.createEntityManagerFactory("BladeCDR");
		} catch (Exception e) {
			sipLogger.info("AnalyticsJmsListener: Failed to create EntityManagerFactory: " + e.getMessage());
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
			sipLogger.warning("AnalyticsJmsListener: Received non-ObjectMessage, ignoring");
			return;
		}

		EntityManager em = null;
		try {
			ObjectMessage objectMessage = (ObjectMessage) message;
			Serializable object = objectMessage.getObject();

			em = emf.createEntityManager();

			if (object instanceof Application) {
				sipLogger.info("AnalyticsJmsListener.onMessage - persisting...\n"
						+ Logger.serializeObject((Application) object));
				persistApplication(em, (Application) object);
			} else if (object instanceof Session) {
				sipLogger.info(
						"AnalyticsJmsListener.onMessage - persisting...\n" + Logger.serializeObject((Session) object));
				persistSession(em, (Session) object);
			} else if (object instanceof Event) {
				sipLogger.info(
						"AnalyticsJmsListener.onMessage - persisting...\n" + Logger.serializeObject((Event) object));
				persistEvent(em, (Event) object);
			} else {
				sipLogger.info("AnalyticsJmsListener.onMessage - persisting...\n" + object);
				sipLogger.info("AnalyticsJmsListener: Unknown object type: " + object.getClass().getName());
			}

		} catch (Exception ex) {
			sipLogger.severe("AnalyticsJmsListener: JMS error: " + ex.getMessage());
			sipLogger.severe(ex);
		} finally {
			if (em != null && em.isOpen()) {
				em.close();
			}
		}
	}

	private void persistApplication(EntityManager em, Application application) {
//		em.getTransaction().begin();
		em.persist(application);
//		em.getTransaction().commit();
		sipLogger.info("AnalyticsJmsListener: Persisted Application id=" + application.getId());
	}

	private void persistSession(EntityManager em, Session session) {
//		em.getTransaction().begin();
		em.persist(session);
//		em.getTransaction().commit();
		sipLogger.info("AnalyticsJmsListener: Persisted Session id=" + session.getId());
	}

	private void persistEvent(EntityManager em, Event event) {
		// Use the custom persistEvent method to handle AttributePK.eventId update
		event.persistEvent(em);
		sipLogger.info("AnalyticsJmsListener: Persisted Event id=" + event.getId());
	}

}
