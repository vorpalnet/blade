package org.vorpal.blade.services.transfer;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.b2bua.Passthru;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.logging.ConsoleColors;
import org.vorpal.blade.framework.v2.transfer.AttendedTransfer;
import org.vorpal.blade.framework.v2.transfer.BlindTransfer;
import org.vorpal.blade.framework.v2.transfer.ConferenceTransfer;
import org.vorpal.blade.framework.v2.transfer.TransferInitialInvite;
import org.vorpal.blade.framework.v2.transfer.TransferListener;
import org.vorpal.blade.framework.v2.transfer.TransferSettings;
import org.vorpal.blade.framework.v2.transfer.api.TransferAPI;

/**
 * This class implements an example B2BUA with transfer capabilities.
 * 
 * @author Jeff McDonald
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
// For debugging, listen on session creation and destruction
public class TransferServlet extends B2buaServlet
		implements B2buaListener, TransferListener, SipApplicationSessionListener, SipSessionListener {
//public class TransferServlet extends B2buaServlet implements B2buaListener, TransferListener {

	private static final long serialVersionUID = 1L;
	// public class TransferServlet extends B2buaServlet {
	public static SettingsManager<TransferSettings> settingsManager;

	public static SettingsManager<TransferSettings> getSettingsManager() {
		return settingsManager;
	}

	public static void setSettingsManager(SettingsManager<TransferSettings> settingsManager) {
		TransferServlet.settingsManager = settingsManager;
	}

//	public void showProperties(SipServletContextEvent event) {
//		String key, value;
//
//		sipLogger.info("System.getenv().get():");
//		for (String name : System.getenv().keySet()) {
//			sipLogger.info("\t" + name + "=" + System.getenv().get(name));
//		}
//
//		sipLogger.info("servletContext.getInitParameter():");
//
//		Iterator<String> itr = event.getServletContext().getInitParameterNames().asIterator();
//		while (itr.hasNext()) {
//			key = itr.next();
//			value = event.getServletContext().getInitParameter(key);
//			sipLogger.info("\t" + key + "=" + value);
//		}
//
//		sipLogger.info("servletContext.getAttribute():");
//		itr = event.getServletContext().getAttributeNames().asIterator();
//		while (itr.hasNext()) {
//			key = itr.next();
//			value = event.getServletContext().getAttribute(key).toString();
//			sipLogger.info("\t" + key + "=" + value);
//		}
//
//	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, TransferSettings.class, new TransferSettingsSample());

		// Quirky visibility problem
		TransferAPI.settings = settingsManager;

		sipLogger.info("TransferServlet.servletCreated");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("TransferServlet.servletDestroyed");
			settingsManager.unregister();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Callflow chooseCallflowStyle(String ts) {
		Callflow callflow;

		switch ((ts != null) ? ts : "none") {
		case "conference":
			callflow = new ConferenceTransfer(this, settingsManager.getCurrent());
			break;
		case "attended":
			callflow = new AttendedTransfer(this, settingsManager.getCurrent());
			break;
		case "blind":
			callflow = new BlindTransfer(this, settingsManager.getCurrent());
			break;
		default: // null & none
			callflow = new Passthru(this);
		}

		return callflow;
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;
		TransferSettings settings = settingsManager.getCurrent();

		switch (request.getMethod()) {
		case "INVITE":
			if (request.isInitial()) {
				callflow = new TransferInitialInvite(this);
			}
			break;

		case "REFER":
			if (request.getApplicationSession().getAttribute("INITIAL_REFER") == null) {
				request.getApplicationSession().setAttribute("INITIAL_REFER", request);
			}
			// find matching translation, if defined
			Translation t = settings.findTranslation(request);
			String ts = null;
			if (t != null) {

				if (t.getAttributes() == null) {
					t.setAttributes(new HashMap<String, String>());
				}

				ts = (String) t.getAttribute("style");

				if (ts == null) {
					ts = settings.getDefaultTransferStyle().toString();
				}

				if (ts == null) {
					ts = "blind";
				}

				callflow = this.chooseCallflowStyle(ts);

				sipLogger.finer(request, "callflow=" + callflow.getClass().getName());

				sipLogger.finer(request, "Found translation, id=" + t.getId() + //
						", description=" + t.getDescription() + //
						", attributes=" + Arrays.asList(t.getAttributes()) + //
						", style=" + ts + //
						", callflow=" + callflow.getClass().getSimpleName());
			} else {

				if (true == settings.getTransferAllRequests()
						&& null != (ts = settings.getDefaultTransferStyle().toString())) {
					callflow = this.chooseCallflowStyle(ts);
				} else {
					sipLogger.finer(request,
							"No translation defined in configuration file; will not process REFER requests.");
				}

			}
			break;
		}

		callflow = (callflow != null) ? callflow : super.chooseCallflow(request);

		return callflow;
	}

	@Override
	public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {

		try {

			if (sipLogger.isLoggable(Level.INFO)) {
				URI ruri = inboundRefer.getRequestURI();
				String from = inboundRefer.getFrom().toString();
				String to = inboundRefer.getTo().toString();
				String referTo = inboundRefer.getHeader("Refer-To");
				String referredBy = inboundRefer.getHeader("Referred-By");

				sipLogger.info(inboundRefer, "TransferServlet.transferRequested - ruri=" + ruri + ", from=" + from
						+ ", to=" + to + ", referTo=" + referTo + ", referredBy=" + referredBy);
			}

		} catch (Exception e) {
			sipLogger.severe(inboundRefer, e);
		}

	}

	@Override
	public void transferInitiated(SipServletRequest outboundRequest) throws ServletException, IOException {

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundRequest,
					"TransferServlet.transferInitiated - method=" + outboundRequest.getMethod());
		}

	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(response, "TransferServlet.transferCompleted - status=" + response.getStatus() + " "
					+ response.getReasonPhrase());
		}

	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(response, "TransferServlet.transferDeclined - status=" + response.getStatus() + " "
					+ response.getReasonPhrase());
		}
	}

	@Override
	public void transferAbandoned(SipServletRequest request) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(request, "TransferServlet.transferAbandoned - method=" + request.getMethod());
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {

		if (sipLogger.isLoggable(Level.INFO)) {
			String ruri = outboundRequest.getRequestURI().toString();
			String from = outboundRequest.getFrom().toString();
			String to = outboundRequest.getTo().toString();

			sipLogger.info(outboundRequest,
					"TransferServlet.callStarted - ruri=" + ruri + ", from=" + from + ", to=" + to);
		}

	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundResponse, "TransferServlet.callAnswered - status=" + outboundResponse.getStatus()
					+ " " + outboundResponse.getReasonPhrase());
		}

	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundRequest, "TransferServlet.callConnected - method=" + outboundRequest.getMethod());
		}
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundRequest, "TransferServlet.callCompleted - method=" + outboundRequest.getMethod());
		}
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundResponse, "TransferServlet.callDeclined - status=" + outboundResponse.getStatus());
		}
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundRequest, "TransferServlet.callAbandoned - method=" + outboundRequest.getMethod());
		}
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {

		if (sipLogger.isLoggable(Level.FINEST)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finest(appSession, ConsoleColors.GREEN_BRIGHT + "TransferServlet.sessionCreated - appSessionId="
					+ event.getApplicationSession().getId() + ConsoleColors.RESET);
		}

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {

			String id = null;
			if (event.getApplicationSession() != null) {
				id = event.getApplicationSession().getId();
			}

			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finest(appSession, ConsoleColors.RED_BRIGHT + "TransferServlet.sessionDestroyed - appSessionId="
					+ id + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {

			String id = null;
			if (event.getApplicationSession() != null) {
				id = event.getApplicationSession().getId();
			}

			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finest(appSession, "TransferServlet.sessionExpired - appSessionId=" + id);
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {

			String id = null;
			if (event.getApplicationSession() != null) {
				id = event.getApplicationSession().getId();
			}

			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finest(appSession, "TransferServlet.sessionReadyToInvalidate - appSessionId=" + id);
		}
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			String id = null;
			if (event.getSession() != null) {
				id = event.getSession().getId();
			}

			SipSession sipSession = event.getSession();
			sipLogger.finest(sipSession, "TransferServlet.sessionCreated - sipSessionId=" + id);
		}
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			String id = null;
			if (event.getSession() != null) {
				id = event.getSession().getId();
			}

			SipSession sipSession = event.getSession();
			sipLogger.finest(sipSession, "TransferServlet.sessionDestroyed - sipSessionId=" + id);
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			String id = null;
			if (event.getSession() != null) {
				id = event.getSession().getId();
			}

			SipSession sipSession = event.getSession();
			sipLogger.finest(sipSession, "TransferServlet.sessionReadyToInvalidate - sipSessionId=" + id);
		}
	}

}
