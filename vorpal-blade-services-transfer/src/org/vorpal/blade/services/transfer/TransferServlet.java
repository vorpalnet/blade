package org.vorpal.blade.services.transfer;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.b2bua.Passthru;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.services.transfer.callflows.AttendedTransfer;
import org.vorpal.blade.services.transfer.callflows.BlindTransfer;
import org.vorpal.blade.services.transfer.callflows.ConferenceTransfer;
import org.vorpal.blade.services.transfer.callflows.TransferInitialInvite;
import org.vorpal.blade.services.transfer.callflows.TransferListener;

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
//public class TransferServlet extends B2buaServlet
//implements B2buaListener, TransferListener, SipApplicationSessionListener, SipSessionListener {
public class TransferServlet extends B2buaServlet implements B2buaListener, TransferListener {

	private static final long serialVersionUID = 1L;
	// public class TransferServlet extends B2buaServlet {
	public static SettingsManager<TransferSettings> settingsManager;

	public static SettingsManager<TransferSettings> getSettingsManager() {
		return settingsManager;
	}

	public static void setSettingsManager(SettingsManager<TransferSettings> settingsManager) {
		TransferServlet.settingsManager = settingsManager;
	}

	public void showProperties(SipServletContextEvent event) {
		String key, value;

		sipLogger.info("System.getenv().get():");
		for (String name : System.getenv().keySet()) {
			sipLogger.info("\t" + name + "=" + System.getenv().get(name));
		}

		sipLogger.info("servletContext.getInitParameter():");

		Iterator<String> itr = event.getServletContext().getInitParameterNames().asIterator();
		while (itr.hasNext()) {
			key = itr.next();
			value = event.getServletContext().getInitParameter(key);
			sipLogger.info("\t" + key + "=" + value);
		}

		sipLogger.info("servletContext.getAttribute():");
		itr = event.getServletContext().getAttributeNames().asIterator();
		while (itr.hasNext()) {
			key = itr.next();
			value = event.getServletContext().getAttribute(key).toString();
			sipLogger.info("\t" + key + "=" + value);
		}

		try {
			// Get ServerConfiguration

//			InitialContext ctx = new InitialContext();
//			MBeanServer mBeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/runtime");
//			ObjectName ServerConfiguration = (ObjectName) mBeanServer
//					.getAttribute(new ObjectName(RuntimeServiceMBean.OBJECT_NAME), "ServerConfiguration");
//			String port = mBeanServer.getAttribute(ServerConfiguration, "ListenPort").toString();
//			sipLogger.severe("ListenPort=" + port);

		} catch (Exception e) {
			sipLogger.severe(e);
		}

	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, TransferSettings.class, new TransferSettingsSample());
		sipLogger.info("servletCreated...");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("servletDestroyed...");
			settingsManager.unregister();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Callflow chooseCallflowStyle(String ts) {
		Callflow callflow;

		switch ((ts != null) ? ts : "none") {
		case "conference":
			callflow = new ConferenceTransfer(this);
			break;
		case "attended":
			callflow = new AttendedTransfer(this);
			break;
		case "blind":
			callflow = new BlindTransfer(this);
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

//				sipLogger.finer(request, "translation found!");

				if (t.getAttributes() == null) {
					t.setAttributes(new HashMap<String, String>());
				}

				ts = (String) t.getAttribute("style");
//				sipLogger.finer(request, "style=" + ts);

				if (ts == null) {
					ts = settings.defaultTransferStyle.toString();
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

			SipApplicationSession appSession = inboundRefer.getApplicationSession();

			// save X-Previous-DN-Tmp for use later
			URI referTo = inboundRefer.getAddressHeader("Refer-To").getURI();
			appSession.setAttribute("Refer-To", referTo);

			if (sipLogger.isLoggable(Level.INFO)) {
				URI ruri = inboundRefer.getRequestURI();
				String from = inboundRefer.getFrom().toString();
				String to = inboundRefer.getTo().toString();
				String referredBy = inboundRefer.getHeader("Referred-By");
				sipLogger.info(inboundRefer, "TransferServlet.transferRequested - ruri=" + ruri + ", from=" + from
						+ ", to=" + to + ", referTo=" + referTo + ", referredBy=" + referredBy);
			}

		} catch (Exception e) {
			sipLogger.severe(inboundRefer, e);
		}

	}

	@Override
	public void transferInitiated(SipServletRequest outboundInvite) throws ServletException, IOException {

		SipApplicationSession appSession = outboundInvite.getApplicationSession();

		// Set Header X-Original-DN
		URI xOriginalDN = (URI) appSession.getAttribute("X-Original-DN");
		outboundInvite.setHeader("X-Original-DN", xOriginalDN.toString());
		sipLogger.finer(outboundInvite, Color.YELLOW_BOLD_BRIGHT(
				"TransferServlet.transferInitiated - setting INVITE header X-Original-DN: " + xOriginalDN.toString()));

		// Set Header X-Previous-DN
		URI xPreviousDN = (URI) appSession.getAttribute("X-Previous-DN");
		outboundInvite.setHeader("X-Previous-DN", xPreviousDN.toString());
		sipLogger.finer(outboundInvite, Color.YELLOW_BOLD_BRIGHT(
				"TransferServlet.transferInitiated - setting INVITE header X-Previous-DN: " + xPreviousDN.toString()));

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundInvite, "TransferServlet.transferInitiated - method=" + outboundInvite.getMethod());
		}

	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {

		SipApplicationSession appSession = response.getApplicationSession();

		SipSession callee = response.getSession();
		callee.setAttribute("userAgent", "callee");
		SipSession caller = Callflow.getLinkedSession(callee);
		caller.setAttribute("userAgent", "caller");

		// now update X-Previous-DN for future use after success transfer
		URI referTo = (URI) appSession.getAttribute("Refer-To");
		appSession.setAttribute("X-Previous-DN", referTo);
		sipLogger.finer(response, Color
				.YELLOW_BOLD_BRIGHT("TransferServlet.transferComplete - saving X-Previous-DN: " + referTo.toString()));

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(response, "TransferServlet.transferCompleted - status=" + response.getStatus());
		}

	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(response, "TransferServlet.transferDeclined - status=" + response.getStatus());
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

		try {

			SipApplicationSession appSession = outboundRequest.getApplicationSession();

			// save X-Original-DN to memory
			URI xOriginalDN = outboundRequest.getTo().getURI();
			appSession.setAttribute("X-Original-DN", xOriginalDN);
			sipLogger.finer(outboundRequest, Color.YELLOW_BOLD_BRIGHT(
					"TransferServlet.callStarted - Saving X-Original-DN: " + xOriginalDN + " to memory."));

			// save X-Previous-DN to memory
			URI xPreviousDN = outboundRequest.getRequestURI();
			appSession.setAttribute("X-Previous-DN", xPreviousDN);
			sipLogger.finer(outboundRequest, Color.YELLOW_BOLD_BRIGHT(
					"TransferServlet.callStarted - Saving X-Previous-DN: " + xPreviousDN + " to memory."));

			// For Transfer REST API
			// save outbound request for REST API Session/Dialog
			outboundRequest.getSession().setAttribute("initial_invite", outboundRequest);
			SipServletRequest aliceRequest = this.getIncomingRequest(outboundRequest);
			aliceRequest.getSession().setAttribute("sipAddress", aliceRequest.getFrom());
			outboundRequest.getSession().setAttribute("sipAddress", outboundRequest.getTo());

			if (sipLogger.isLoggable(Level.INFO)) {
				String ruri = outboundRequest.getRequestURI().toString();
				String from = outboundRequest.getFrom().toString();
				String to = outboundRequest.getTo().toString();

				sipLogger.info(outboundRequest,
						"TransferServlet.callStarted - ruri=" + ruri + ", from=" + from + ", to=" + to);
			}

		} catch (Exception e) {
			sipLogger.severe(outboundRequest, e);
		}

	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		SipSession caller = outboundResponse.getSession();
		caller.setAttribute("userAgent", "caller");
		SipSession callee = Callflow.getLinkedSession(caller);
		callee.setAttribute("userAgent", "callee");

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundResponse, "TransferServlet.callAnswered - status=" + outboundResponse.getStatus());
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

//	@Override
//	public void sessionCreated(SipApplicationSessionEvent event) {
//
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipApplicationSession appSession = event.getApplicationSession();
//			sipLogger.finer(appSession, ConsoleColors.GREEN_BRIGHT + "appSession created..." + ConsoleColors.RESET);
//		}
//
//	}
//
//	@Override
//	public void sessionDestroyed(SipApplicationSessionEvent event) {
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipApplicationSession appSession = event.getApplicationSession();
//			sipLogger.finer(appSession, ConsoleColors.RED_BRIGHT + "appSession destroyed..." + ConsoleColors.RESET);
//		}
//	}
//
//	@Override
//	public void sessionExpired(SipApplicationSessionEvent event) {
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipApplicationSession appSession = event.getApplicationSession();
//			sipLogger.finer(appSession, "appSession expired...");
//		}
//	}
//
//	@Override
//	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipApplicationSession appSession = event.getApplicationSession();
//			sipLogger.finer(appSession, "appSession readyToInvalidate...");
//		}
//	}
//
//	@Override
//	public void sessionCreated(SipSessionEvent event) {
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipSession sipSession = event.getSession();
//			sipLogger.finer(sipSession, "sipSession created...");
//		}
//	}
//
//	@Override
//	public void sessionDestroyed(SipSessionEvent event) {
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipSession sipSession = event.getSession();
//			sipLogger.finer(sipSession, "sipSession destroyed...");
//
//		}
//	}
//
//	@Override
//	public void sessionReadyToInvalidate(SipSessionEvent event) {
//		if (sipLogger.isLoggable(Level.FINER)) {
//			SipSession sipSession = event.getSession();
//			sipLogger.finer(sipSession, "sipSession readyToInvalidate...");
//		}
//	}

}
