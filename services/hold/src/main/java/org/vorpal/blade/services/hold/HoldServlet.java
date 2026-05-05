package org.vorpal.blade.services.hold;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.CallflowHoldRelease;
import org.vorpal.blade.framework.v2.callflow.CallflowHold;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * This class implements an example B2BUA with transfer capabilities.
 * 
 * @author Jeff McDonald
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class HoldServlet extends B2buaServlet {

	private static final long serialVersionUID = 1L;
	public static SettingsManager<HoldSettings> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, HoldSettings.class, new HoldSettingsSample());
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

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		switch (request.getMethod()) {
		case "INVITE":
			callflow = new CallflowHold();
			break;

		case "CANCEL":
		case "BYE":
			callflow = new CallflowHoldRelease();
			break;

		case "ACK":
			// In-dialog ACKs are absorbed by the parked sendResponse callback;
			// any ACK that reaches chooseCallflow is an orphan — let the
			// framework drop it silently (it special-cases null + ACK).
			break;

		default:
			callflow = new HoldMethodNotAllowed();
		}

		return callflow;
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
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

}
