package org.vorpal.blade.framework;

import java.io.IOException;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.framework.logging.Logger.Direction;

/**
 * This abstract SipServlet is designed to implement the features of the BLADE
 * asynchronous APIs (lambda expressions). Extend and implement this class to
 * create your own specialized SipServlet class. See B2buaServlet as an example.
 * 
 * @author Jeff McDonald
 *
 */
public abstract class AsyncSipServlet extends SipServlet
		implements SipServletListener, ServletContextListener, TimerListener {

	private static final long serialVersionUID = 1L;
	private SipServletContextEvent event;
	protected static Logger sipLogger;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;

	/**
	 * Called when the SipServlet has been created.
	 * 
	 * @param event
	 */
	protected abstract void servletCreated(SipServletContextEvent event);

	/**
	 * Called when the SipServlet has been destroyed.
	 * 
	 * @param event
	 */
	protected abstract void servletDestroyed(SipServletContextEvent event);

	/**
	 * Implement this method to choose various Callflow objects for incoming
	 * requests that do not already have a callflow defined.
	 * 
	 * @param request
	 * @return Callflow the chosen callflow object
	 * @throws ServletException
	 * @throws IOException
	 */
	protected abstract Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException;

	@Override
	final public void servletInitialized(SipServletContextEvent event) {
		this.event = event;
		sipLogger = LogManager.getLogger(event);
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		timerService = (TimerService) event.getServletContext().getAttribute("javax.servlet.sip.TimerService");

		Callflow.setLogger(sipLogger);
		Callflow.setSipFactory(sipFactory);
		Callflow.setSipUtil(sipUtil);
		Callflow.setTimerService(timerService);

		try {
			servletCreated(event);
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
		}

	}

	/**
	 * Override this method to handle call events that may fall outside the scope of
	 * your defined callflows.
	 * 
	 * @param message a modifiable message object, either a request or response
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callEvent(SipServletMessage message) throws ServletException, IOException {
		// override this method
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// do nothing;
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		try {
			servletDestroyed(event);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow;
		Callback<SipServletRequest> requestLambda;

		try {
			requestLambda = Callflow.pullCallback(request);
			if (requestLambda != null) {
				String name = requestLambda.getClass().getSimpleName();
				Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, name);
				requestLambda.accept(request);
			} else {
				callflow = chooseCallflow(request);

				if (callflow == null) {
					if (request.getMethod().equals("ACK")) {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
					} else {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
						SipServletResponse response = request.createResponse(501);
						response.send();
						Callflow.getLogger().superArrow(Direction.SEND, null, response, "null");
						Callflow.getLogger().warning("No registered callflow for request method " + request.getMethod()
								+ ", consider modifying the 'chooseCallflow' method.");
					}
				} else {
					callflow.processWrapper(request);
				}
			}
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
			throw e;
		}
	}

	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		Callback<SipServletResponse> callback;
		try {

			callback = Callflow.pullCallback(response);
			if (callback != null) {
				Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, callback.getClass().getSimpleName());
				callback.accept(response);
			} else {
				Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, "null");
			}

		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
			throw e;
		}

	}

	@SuppressWarnings("unchecked")
	@Override
	public void timeout(ServletTimer timer) {
		try {
			Callback<ServletTimer> callback;
			callback = (Callback<ServletTimer>) timer.getInfo();
			callback.accept(timer);
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
		}
	}

	/**
	 * @return the sipLogger
	 */
	public static Logger getSipLogger() {
		return sipLogger;
	}

	/**
	 * @return the sipFactory
	 */
	public static SipFactory getSipFactory() {
		return sipFactory;
	}

	/**
	 * @return the sipUtil
	 */
	public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	/**
	 * @return the timerService
	 */
	public static TimerService getTimerService() {
		return timerService;
	}

	/**
	 * This is an alternate hashing algorithm that can be used during 
	 * the @SipApplicationKey method.
	 * 
	 * @param string
	 * @return a long number written as a hexadecimal string
	 */
	public static String hash(String string) {
		long h = 1125899906842597L; // prime
		int len = string.length();

		for (int i = 0; i < len; i++) {
			h = 31 * h + string.charAt(i);
		}

		return Long.toHexString(h);
	}

}
