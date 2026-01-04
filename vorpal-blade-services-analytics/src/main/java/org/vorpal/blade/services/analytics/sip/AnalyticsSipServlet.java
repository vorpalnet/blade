package org.vorpal.blade.services.analytics.sip;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;

import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.naming.InitialContext;
import javax.persistence.EntityManagerFactory;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Color;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class AnalyticsSipServlet extends B2buaServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<AnalyticsConfig> settingsManager;

	public final static String JNDI_FACTORY = "weblogic.jndi.WLInitialContextFactory";
	public final static String JMS_FACTORY = "jms/TestConnectionFactory";
	public final static String QUEUE = "jms/TestJMSQueue";

	private static InitialContext ctx;
	private static QueueConnectionFactory qconFactory;
	private static QueueConnection qcon;
	private static Queue queue;
	public static QueueSession qsession;
	public static QueueSender qsender;

	// JPA
	public static EntityManagerFactory emf = null;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {

		System.out.println(Color.RED("AnalyticsSipServlet.servletCreated - B2buaServlet"));

		try {

			settingsManager = new SettingsManager<AnalyticsConfig>(event, AnalyticsConfig.class,
					new AnalyticsConfigSample());

			ctx = new InitialContext();
			qconFactory = (QueueConnectionFactory) ctx.lookup(JMS_FACTORY);
			qcon = qconFactory.createQueueConnection();
			qsession = qcon.createQueueSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
			queue = (Queue) ctx.lookup(QUEUE);
			qsender = qsession.createSender(queue);
			qcon.start();

		} catch (Exception e) {
			sipLogger.severe(e);
		}

// doesn't work		
//		try {
//
//			if (emf == null) {
//				
//				Thread.sleep(5000);
//				
//				
//				emf = Persistence.createEntityManagerFactory("BladeCDR");
//				
//				EntityManager em = emf.createEntityManager();
//				em.getTransaction().begin(); // Start a transaction
//				Configuration configuration = new Configuration();
//				
//				configuration.setName(SettingsManager.getApplicationName());
//				configuration.setVersion(SettingsManager.getApplicationVersion());
//				configuration.setDomain(SettingsManager.getDomainName());
//				configuration.setClusters(SettingsManager.getClusterName());
//				configuration.setServer(SettingsManager.getServerName());
//				configuration.setCreatedAt( Timestamp.from(Instant.now()));
//				
//				String strId = "" //
//						+ SettingsManager.getServerName() //
//						+ SettingsManager.getApplicationName() //
//						+ SettingsManager.getApplicationVersion() //
//						+ settingsManager.getCurrent().hashCode()
//						;
//				configuration.setId(strId.hashCode());
//				configuration.setData(Logger.serializeObject(settingsManager.getCurrent()).getBytes());
//
//				// What if it already exists?
//				em.persist(configuration);
//				em.close();
//
//			}
//
//		} catch (Exception ex) {
//			ex.printStackTrace();
//		}

	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		System.out.println(Color.RED("AnalyticsSipServlet.servletDestroyed - B2buaServlet"));

		try {
			qsender.close();
			qsession.close();
			qcon.close();
		} catch (JMSException e) {
			sipLogger.severe(e);
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callStarted");

		try {

//			MapMessage mapMessage = AnalyticsSipServlet.qsession.createMapMessage();
//
//			mapMessage.setString("X-Vorpal-Session",
//					(String) outboundRequest.getApplicationSession().getAttribute("X-Vorpal-Session"));
//			mapMessage.setString("X-Vorpal-Timestamp",
//					(String) outboundRequest.getApplicationSession().getAttribute("X-Vorpal-Timestamp"));
//
//			mapMessage.setString("event", "callStarted");
//			mapMessage.setString("From", outboundRequest.getHeader("From"));
//			mapMessage.setString("To", outboundRequest.getHeader("To"));
//
//			mapMessage.setString("cluster", SettingsManager.getClusterName());
//			mapMessage.setString("domain", SettingsManager.getDomainName());


//			MapMessage mapMessage = AnalyticsSipServlet.qsession.createMapMessage();
//
//			Session session = new Session();
//			SessionPK sessionPK = new SessionPK();
//			sessionPK.setName("12345678");
//			sessionPK.setCreated(Date.from(Instant.now()));
//			session.setId(sessionPK);
//
//			Event event = new Event();
//			event.setAppName("b2bua");
//			event.setAppVersion("1.0.1");
//			event.setDomain("replicated");
//			event.setCreated(Timestamp.from(Instant.now()));
//			event.setHost("localhost");
//			event.setName("callStarted");
//			event.setServer("engine1");
//
//			event.setSession(session);
//
////			EventAttributePK eventAttributePK = new EventAttributePK();
////			eventAttributePK.setEventId(serialVersionUID);
//
//			mapMessage.setObject("event", event);
//

			
			
//			AnalyticsSipServlet.qsender.send(mapMessage);

			
			
			
			
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {

		sipLogger.fine(outboundResponse, "AnalyticsSipServlet.callAnswered");

//		TextMessage msg;
//		try {
//			msg = AnalyticsSipServlet.qsession.createTextMessage();
//			msg.setText("callAnswered");
//			AnalyticsSipServlet.qsender.send(msg);
//		} catch (JMSException e) {
//			e.printStackTrace();
//		}
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {

		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callConnected");

//		TextMessage msg;
//		try {
//			msg = AnalyticsSipServlet.qsession.createTextMessage();
//			msg.setText("callConnected");
//			AnalyticsSipServlet.qsender.send(msg);
//		} catch (JMSException e) {
//			e.printStackTrace();
//		}
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {

		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callCompleted");

//		TextMessage msg;
//		try {
//			msg = AnalyticsSipServlet.qsession.createTextMessage();
//			msg.setText("callCompleted");
//			AnalyticsSipServlet.qsender.send(msg);
//		} catch (JMSException e) {
//			e.printStackTrace();
//		}
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {

		sipLogger.fine(outboundResponse, "AnalyticsSipServlet.callDeclined");

//		TextMessage msg;
//		try {
//			msg = AnalyticsSipServlet.qsession.createTextMessage();
//			msg.setText("callDeclined");
//			AnalyticsSipServlet.qsender.send(msg);
//		} catch (JMSException e) {
//			e.printStackTrace();
//		}
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {

		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callAbandoned");

//		TextMessage msg;
//		try {
//			msg = AnalyticsSipServlet.qsession.createTextMessage();
//			msg.setText("callAbandoned");
//			AnalyticsSipServlet.qsender.send(msg);
//		} catch (JMSException e) {
//			e.printStackTrace();
//		}
	}

}
