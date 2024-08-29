package org.vorpal.blade.services.transfer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.Address;
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

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.b2bua.Passthru;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.framework.logging.ConsoleColors;
import org.vorpal.blade.framework.transfer.AttendedTransfer;
import org.vorpal.blade.framework.transfer.BlindTransfer;
import org.vorpal.blade.framework.transfer.ConferenceTransfer;
import org.vorpal.blade.framework.transfer.TransferInitialInvite;
import org.vorpal.blade.framework.transfer.TransferListener;
import org.vorpal.blade.services.transfer.TransferSettings.TransferStyle;

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

	private Callflow chooseCallflowStyle(TransferSettings.TransferStyle ts) {
		Callflow callflow;

		switch ((ts != null) ? ts : TransferStyle.none) {
		case conference:
			callflow = new ConferenceTransfer(this);
			break;
		case attended:
			callflow = new AttendedTransfer(this);
			break;
		case blind:
			callflow = new BlindTransfer(this);
			break;
		default: // null & none
			callflow = new Passthru(this);
		}

		return callflow;
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
				callflow = new TransferInitialInvite();
			}
			break;

		case "REFER":
			if (request.getApplicationSession().getAttribute("INITIAL_REFER") == null) {
				request.getApplicationSession().setAttribute("INITIAL_REFER", request);
			}
			// find matching translation, if defined
			Translation t = settings.findTranslation(request);
			if (t != null) {
				String ts = (String) t.getAttribute("style");
				callflow = this.chooseCallflowStyle(ts);

				sipLogger.finer(request, "Found translation, id=" + t.getId() + //
						", description=" + t.getDescription() + //
						", attributes=" + Arrays.asList(t.getAttributes()) + //
						", style=" + ts + //
						", callflow=" + callflow.getClass().getSimpleName());
			} else {
				sipLogger.finer(request, "No translation found.");
			}
			break;
		}

		callflow = (callflow != null) ? callflow : super.chooseCallflow(request);

		return callflow;
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		SipApplicationSession appSession = outboundRequest.getApplicationSession();

		// save X-Original-DN to memory
		URI xOriginalDN = outboundRequest.getTo().getURI();
		appSession.setAttribute("X-Original-DN", xOriginalDN);
		
		// save X-Previous-DN to memory
		URI xPreviousDN = outboundRequest.getRequestURI();
		appSession.setAttribute("X-Previous-DN", xPreviousDN);
		
	}

	@Override
	public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {
		SipApplicationSession appSession = inboundRefer.getApplicationSession();
		
		// save X-Previous-DN-Tmp for use later
		URI referTo = inboundRefer.getAddressHeader("Refer-To").getURI();
		appSession.setAttribute("Refer-To", referTo);
	}

	@Override
	public void transferInitiated(SipServletRequest outboundInvite) throws ServletException, IOException {
		SipApplicationSession appSession = outboundInvite.getApplicationSession();

		// Set Header X-Original-DN
		Address xOriginalDN = (Address) appSession.getAttribute("X-Original-DN");
		outboundInvite.setAddressHeader("X-Original-DN", xOriginalDN);

		// Set Header X-Previous-DN
		Address xPreviousDN = (Address) appSession.getAttribute("X-Previous-DN");
		outboundInvite.setAddressHeader("X-Previous-DN", xPreviousDN);
		
		// now update X-Previous-DN for future use
		URI referTo = (URI) appSession.getAttribute("Refer-To");
		appSession.setAttribute("X-Previous-DN", referTo);
	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void transferAbandoned(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	public static SettingsManager<TransferSettings> getSettingsManager() {
		return settingsManager;
	}

	public static void setSettingsManager(SettingsManager<TransferSettings> settingsManager) {
		TransferServlet.settingsManager = settingsManager;
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {

		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, ConsoleColors.GREEN_BRIGHT + "appSession created..." + ConsoleColors.RESET);
		}

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, ConsoleColors.RED_BRIGHT + "appSession destroyed..." + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession expired...");
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession readyToInvalidate...");
		}
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession created...");
		}
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession destroyed...");

		}
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession readyToInvalidate...");
		}
	}

}
