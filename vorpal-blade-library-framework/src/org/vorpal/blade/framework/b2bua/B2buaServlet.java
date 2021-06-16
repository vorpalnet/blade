/**
 *  BLADE - Blended Layer Application Development Environment
 *  Copyright (C) 2018-2021  Vorpal Networks, LLC
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.vorpal.blade.framework.b2bua;

import java.io.IOException;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
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
 * @author Jeff McDonald
 *
 */
public abstract class B2buaServlet extends SipServlet implements B2buaListener, SipServletListener, ServletContextListener, TimerListener {
	private static final long serialVersionUID = 1L;
	private SipServletContextEvent event;
	protected static Logger sipLogger;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;

	@Override
	public void servletInitialized(SipServletContextEvent event) {
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
			this.b2buaCreated(event);
		} catch (Exception e) {
			Callflow.sipLogger.logStackTrace(e);
		}

		sipLogger.severe("Restarting B2buaServlet...");

	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {

	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		try {
			b2buaDestroyed(event);
		} catch (Exception e) {
			Callflow.sipLogger.logStackTrace(e);
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
				Callflow.sipLogger.superArrow(Direction.RECEIVE, request, null, name);
				requestLambda.accept(request);
			} else {
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
				callflow.processWrapper(request);
			}
		} catch (Exception e) {
			Callflow.sipLogger.logStackTrace(e);
			if (e instanceof ServletException) {
				throw new ServletException(e);
			} else if (e instanceof IOException) {
				throw new IOException(e);
			}
		}
	}

	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		Callback<SipServletResponse> callback;
		try {

			callback = Callflow.pullCallback(response);
			if (callback != null) {
				Callflow.sipLogger.superArrow(Direction.RECEIVE, null, response, callback.getClass().getSimpleName());
				callback.accept(response);
			} else {
				Callflow.sipLogger.superArrow(Direction.RECEIVE, null, response, "null");
			}

		} catch (Exception e) {
			Callflow.sipLogger.logStackTrace(e);
			if (e instanceof ServletException) {
				throw new ServletException(e);
			} else if (e instanceof IOException) {
				throw new IOException(e);
			}
		}

	}

	@SuppressWarnings("unchecked")
	@Override
	public void timeout(ServletTimer timer) {
		Callflow.sipLogger.fine("timer invoked... ");

		try {
			Callback<ServletTimer> callback;
			callback = (Callback<ServletTimer>) timer.getInfo();
			callback.accept(timer);
		} catch (Exception e) {
			Callflow.sipLogger.logStackTrace(e);
		}

	}

	@Override
	public void callStarted(SipServletRequest request) {
		request.getApplicationSession().setAttribute("REMOTE_SESSION", request.getSession().getId());
	}

	@Override
	public void calleeEvent(SipServletMessage msg) throws Exception {
	}

	@Override
	public void callerEvent(SipServletMessage msg) throws Exception {
	}

	@Override
	public void callAnswered(SipServletResponse response) throws Exception {
	}

	@Override
	public void callCompleted(SipServletRequest request) throws Exception {
	}

	@Override
	public void b2buaCreated(SipServletContextEvent event) throws Exception {
	}

	@Override
	public void b2buaDestroyed(SipServletContextEvent event) throws Exception {
	}

	@Override
	public void callConnected(SipServletRequest request) throws Exception {
	}

	@Override
	public void callRefused(SipServletResponse response) throws Exception {
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

}
