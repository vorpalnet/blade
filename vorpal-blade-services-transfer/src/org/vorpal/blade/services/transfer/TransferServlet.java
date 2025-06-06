package org.vorpal.blade.services.transfer;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
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

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.b2bua.Passthru;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.logging.ConsoleColors;
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
//public class TransferServlet extends B2buaServlet implements TransferListener {
public class TransferServlet extends B2buaServlet //
		implements TransferListener, SipApplicationSessionListener, SipSessionListener {

	private static final long serialVersionUID = 1L;
	// public class TransferServlet extends B2buaServlet {
	public static SettingsManager<TransferSettings> settingsManager;

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
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {

		try {
//			sipLogger.finer(outboundRequest, "TransferServlet callStarted...");

			SipApplicationSession appSession = outboundRequest.getApplicationSession();
//			sipLogger.finer(outboundRequest, "appSession id=" + appSession.getId());
//			sipLogger.finer(outboundRequest, "appSession indexKeys=" + appSession.getIndexKeys());

			// save X-Original-DN to memory
			URI xOriginalDN = outboundRequest.getTo().getURI();
			appSession.setAttribute("X-Original-DN", xOriginalDN);

			// save X-Previous-DN to memory
			URI xPreviousDN = outboundRequest.getRequestURI();
			appSession.setAttribute("X-Previous-DN", xPreviousDN);

			// For Transfer REST API
			// save outbound request for REST API Session/Dialog
			outboundRequest.getSession().setAttribute("initial_invite", outboundRequest);
			SipServletRequest aliceRequest = this.getIncomingRequest(outboundRequest);
			aliceRequest.getSession().setAttribute("sipAddress", aliceRequest.getFrom());
			outboundRequest.getSession().setAttribute("sipAddress", outboundRequest.getTo());

		} catch (Exception e) {
			sipLogger.severe(outboundRequest, e);
		}

	}

	@Override
	public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {

		try {
//			sipLogger.finer(inboundRefer, "transferRequested...");

			SipApplicationSession appSession = inboundRefer.getApplicationSession();

			// save X-Previous-DN-Tmp for use later
			URI referTo = inboundRefer.getAddressHeader("Refer-To").getURI();
//			sipLogger.finer(inboundRefer, "transferRequested... setting appSession attribute: Refer-To=" + referTo);
			appSession.setAttribute("Refer-To", referTo);

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

		// Set Header X-Previous-DN
		URI xPreviousDN = (URI) appSession.getAttribute("X-Previous-DN");
		outboundInvite.setHeader("X-Previous-DN", xPreviousDN.toString());

	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
//		sipLogger.finer(response, "transferCompleted...");

		SipApplicationSession appSession = response.getApplicationSession();

		SipSession callee = response.getSession();
		callee.setAttribute("userAgent", "callee");
		SipSession caller = Callflow.getLinkedSession(callee);
		caller.setAttribute("userAgent", "caller");

		// now update X-Previous-DN for future use after success transfer
		URI referTo = (URI) appSession.getAttribute("Refer-To");
		appSession.setAttribute("X-Previous-DN", referTo);
	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
//		sipLogger.finer(response, "transferDeclined...");
	}

	@Override
	public void transferAbandoned(SipServletRequest request) throws ServletException, IOException {
//		sipLogger.finer(request, "transferAbandoned...");
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
//		sipLogger.finer(outboundResponse, "callAnswered...");
		SipSession caller = outboundResponse.getSession();
		caller.setAttribute("userAgent", "caller");
		SipSession callee = Callflow.getLinkedSession(caller);
		callee.setAttribute("userAgent", "callee");
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
//		sipLogger.finer(outboundRequest, "callConnected...");
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
//		sipLogger.finer(outboundRequest, "callCompleted...");
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
//		sipLogger.finer(outboundResponse, "callDeclined...");
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
//		sipLogger.finer(outboundRequest, "callAbandoned...");
	}

	public static SettingsManager<TransferSettings> getSettingsManager() {
		return settingsManager;
	}

	public static void setSettingsManager(SettingsManager<TransferSettings> settingsManager) {
		TransferServlet.settingsManager = settingsManager;
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {

		if (sipLogger.isLoggable(Level.FINEST)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, ConsoleColors.GREEN_BRIGHT + "appSession created..." + ConsoleColors.RESET);
		}

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, ConsoleColors.RED_BRIGHT + "appSession destroyed..." + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession expired...");
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession readyToInvalidate...");
		}
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession created...");
		}
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession destroyed...");

		}
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINEST)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession readyToInvalidate...");
		}
	}

}
