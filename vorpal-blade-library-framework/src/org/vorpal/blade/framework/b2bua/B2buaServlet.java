/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.framework.b2bua;

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
 * @author Jeff McDonald
 *
 */
public abstract class B2buaServlet extends SipServlet
		implements B2buaListener, SipServletListener, ServletContextListener, TimerListener {
	private static final long serialVersionUID = 1L;
	private SipServletContextEvent event;
	protected static Logger sipLogger;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;

	@Override
	final public void servletInitialized(SipServletContextEvent event) {
		this.event = event;
		sipLogger = LogManager.getLogger(event.getServletContext());
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		timerService = (TimerService) event.getServletContext().getAttribute("javax.servlet.sip.TimerService");

		Callflow.setLogger(sipLogger);
		Callflow.setSipFactory(sipFactory);
		Callflow.setSipUtil(sipUtil);
		Callflow.setTimerService(timerService);

		try {
			b2buaCreated(event);
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
		}

	}

	@Override
	final public void contextInitialized(ServletContextEvent sce) {
	}

	@Override
	final public void contextDestroyed(ServletContextEvent sce) {
		try {
			b2buaDestroyed(event);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	/**
	 * Override this method to choose different Callflow objects for incoming
	 * requests that do not already have a callflow object assigned.
	 * 
	 * @param request
	 * @return Callflow the chosen callflow object
	 * @throws ServletException
	 * @throws IOException
	 */
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow;

		if (request.getMethod().equals("INVITE")) {
			if (request.isInitial()) {
				callflow = new InitialInvite(this);
			} else {
				callflow = new Reinvite(this);
			}
		} else if (request.getMethod().equals("BYE")) {
			callflow = new Bye(this);
		} else if (request.getMethod().equals("CANCEL")) {
			callflow = new Cancel(this);
		} else {
			callflow = new Passthru(this);
		}

		return callflow;
	}

	@Override
	final protected void doRequest(SipServletRequest request) throws ServletException, IOException {
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
				callflow.processWrapper(request);
			}
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
			throw e;
		}
	}

	@Override
	final protected void doResponse(SipServletResponse response) throws ServletException, IOException {
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
			if (e instanceof ServletException) {
				throw new ServletException(e);
			} else if (e instanceof IOException) {
				throw new IOException(e);
			}
		}

	}

	@SuppressWarnings("unchecked")
	@Override
	final public void timeout(ServletTimer timer) {
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
	final public static Logger getSipLogger() {
		return sipLogger;
	}

	/**
	 * @return the sipFactory
	 */
	final public static SipFactory getSipFactory() {
		return sipFactory;
	}

	/**
	 * @return the sipUtil
	 */
	final public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	/**
	 * @return the timerService
	 */
	final public static TimerService getTimerService() {
		return timerService;
	}

}
