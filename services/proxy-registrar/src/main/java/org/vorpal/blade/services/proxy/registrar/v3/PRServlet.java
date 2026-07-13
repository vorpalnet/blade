package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;

/// The Proxy Registrar — a REGISTER server plus a config-driven forwarder.
///
/// REGISTER requests maintain the location database (one app session per
/// account, indexed by [#sessionKey]). Initial INVITEs fork to every
/// registered contact ([InviteCallflow] over `sendRequestsInParallel`); the
/// b2bua callflows inherited from [B2buaServlet] relay everything in-dialog.
/// With `session:passthru` set in the config, the forwarding callflow
/// stitches the endpoints' Contacts together and drops out of the dialog
/// after setup — proxy-like behavior without the v2 Proxy API.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class PRServlet extends B2buaServlet {
	private static final long serialVersionUID = 2804504496149776315L;
	public static SettingsManager<Settings> settingsManager;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest request) {
		String key = null;

		switch (request.getMethod()) {
		case "REGISTER":
			key = getAccountName(request.getFrom());
			break;
		}

		sipLogger.finer("sessionKey method=" + request.getMethod() + ", key=" + key);
		return key;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<Settings>(event, Settings.class, new SettingsSample());
		sipLogger.finer("PRServlet.servletCreated");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		sipLogger.finer("PRServlet.servletDestroyed...");
		settingsManager.unregister();
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		switch (request.getMethod()) {
		case "REGISTER":
			return new RegisterCallflow();
		case "INVITE":
			if (request.isInitial()) {
				return new InviteCallflow();
			}
			// fall through to the b2bua Reinvite
		default:
			// b2bua dispatch: Reinvite / Terminate / Passthru
			return super.chooseCallflow(request);
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

}
