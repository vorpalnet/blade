package org.vorpal.blade.services.analytics.sip;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.ThreadLocalRandom;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.annotation.SipListener;

import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.services.analytics.jpa.Application;
import org.vorpal.blade.services.analytics.jpa.Attribute;
import org.vorpal.blade.services.analytics.jpa.AttributePK;
import org.vorpal.blade.services.analytics.jpa.Event;
import org.vorpal.blade.services.analytics.jpa.Session;

// order of invocation seems random!
@SipListener
//@WebFilter("/RequestLoggingFilter")
//@WebFilter("/*")
@WebListener
public class AnalyticsListener
		implements ServletContextListener, SipServletListener, SipApplicationSessionListener, SipSessionListener {

	// JPA
	public static EntityManagerFactory emf = null;

	@Override
	public void servletInitialized(SipServletContextEvent event) {
		// this comes before contextInitialized
		
		

		System.out.println("AnalyticsListener.servletInitialized - SipServlet");

//		try {
//
//			if (emf == null) {
//				emf = Persistence.createEntityManagerFactory("BladeCDR");
//			}
//
//		} catch (Exception ex) {
//			System.out.println(Color.RED("AnalyticsListener.servletInitialized - Error: " + ex.getClass().getName()));
//		}

	}

	@Override
	final public void contextInitialized(ServletContextEvent sce) {

		System.out.println("AnalyticsListener.contextInitialized - WebServlet");

//		try {
//
//			if (emf == null) {
//
//				Date created = Date.from(Instant.now());
//
//				emf = Persistence.createEntityManagerFactory("BladeCDR");
//
//				EntityManager em = emf.createEntityManager();
//
////				long sessionId = ThreadLocalRandom.current().nextInt(10000);
//				Long sessionId = ThreadLocalRandom.current().nextLong(10000);
//				int applicationId = ThreadLocalRandom.current().nextInt(1000);
//
//				Date now = Date.from(Instant.now());
//
//				// Application
//				Application application = new Application();
//				application.setId(applicationId);
//				application.setCreated(now);
//				application.setDomain("replicated");
//				application.setHost("gamera");
//				application.setName("b2bua");
//				application.setServer("engine1");
//				application.setVersion("1.0.1");
//
//				em.getTransaction().begin();
//				em.persist(application);
//				em.getTransaction().commit();
//				System.out.println("AnalyticsListener application: \n" + Logger.serializeObject(application));
//
//				// Session
//				Session session = new Session();
//				session.setId(sessionId);
//				session.setApplicationId(applicationId);
//				session.setCreated(now);
//
//				em.getTransaction().begin();
//				em.persist(session);
//				em.getTransaction().commit();
//				System.out.println("persisting session: \n" + Logger.serializeObject(session));
//
//				// Event
//				Event event = new Event();
//				event.setCreated(created);
//				event.setName("callStarted");
//				event.setApplicationId(applicationId);
//				event.setSessionId(sessionId);
//
////				System.out.println("AnalyticsListener event: (before) \n" + Logger.serializeObject(event));
////				em.getTransaction().begin();
////				em.persist(event);
////				em.getTransaction().commit();
////				System.out.println("AnalyticsListener event: (after) \n" + Logger.serializeObject(event));
//
//				AttributePK attrPK = new AttributePK();
//				attrPK.setEventId(event.getId());
//				attrPK.setName("From");
//
//				Attribute from = new Attribute();
//				from.setId(attrPK);
//				from.setValue("sip:alice@vorpal.net");
//				event.addAttribute(from);
//
//				AttributePK attrPK2 = new AttributePK();
//				attrPK2.setName("To");
//				attrPK2.setEventId(event.getId());
//
//				Attribute to = new Attribute();
//				to.setId(attrPK2);
//				to.setValue("sip:bob@vorpal.net");
//				event.addAttribute(to);
//
//				System.out.println(
//						"AnalyticsListener event before (with attributes): \n" + Logger.serializeObject(event));
////				em.getTransaction().begin();
////				em.persist(event);
//				event.persistEvent(em);
////				em.getTransaction().commit();
//				System.out
//						.println("AnalyticsListener event after (with attributes): \n" + Logger.serializeObject(event));
//
//				em.close();
//
//			}
//
//		} catch (Exception ex) {
//			ex.printStackTrace();
//		}

	}

	@Override
	final public void contextDestroyed(ServletContextEvent sce) {
		System.out.println(Color.RED("AnalyticsListener.contextDestroyed - WebServlet"));

		try {

			if (emf != null) {
				emf.close();
			}

		} catch (Exception ex) {
			System.out.println("AnalyticsListener.contextDestroyed - Exception: " + ex.getMessage());
		}

	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		// TODO Auto-generated method stub
		System.out.println(Color.RED("AnalyticsListener.sessionCreated"));

	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		// TODO Auto-generated method stub
		System.out.println(Color.RED("AnalyticsListener.sessionDestroyed"));

	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		// TODO Auto-generated method stub
		System.out.println(Color.RED("AnalyticsListener.sessionReadyToInvalidate"));

	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		// TODO Auto-generated method stub
		System.out.println(Color.RED("AnalyticsListener.sessionCreated"));

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		// TODO Auto-generated method stub
		System.out.println(Color.RED("AnalyticsListener.sessionDestroyed"));

	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		// TODO Auto-generated method stub
		System.out.println(Color.RED("AnalyticsListener.sessionExpired"));

	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		System.out.println(Color.RED("AnalyticsListener.sessionReadyToInvalidate"));
	}

	private ServletContext context;

	public void init(FilterConfig fConfig) throws ServletException {
		this.context = fConfig.getServletContext();
		this.context.log("RequestLoggingFilter initialized");
	}

	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest req = (HttpServletRequest) request;
		Enumeration<String> params = req.getParameterNames();
		while (params.hasMoreElements()) {
			String name = params.nextElement();
			String value = request.getParameter(name);
			this.context.log(req.getRemoteAddr() + "::Request Params::{" + name + "=" + value + "}");
		}

		Cookie[] cookies = req.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				this.context
						.log(req.getRemoteAddr() + "::Cookie::{" + cookie.getName() + "," + cookie.getValue() + "}");
			}
		}
		// pass the request along the filter chain
		chain.doFilter(request, response);
	}

	public void destroy() {
		// we can close resources here
	}

}
