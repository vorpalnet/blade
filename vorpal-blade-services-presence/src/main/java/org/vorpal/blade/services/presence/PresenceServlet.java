package org.vorpal.blade.services.presence;

import java.io.IOException;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class PresenceServlet extends AsyncSipServlet {
	public static SettingsManager<PresenceSettings> settingsManager;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest req) {
		return getAccountName(req.getTo());
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, PresenceSettings.class);
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		// TODO Auto-generated method stub
		settingsManager.unregister();

	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		switch (request.getMethod()) {
		case "SUBSCRIBE":
			callflow = new SubscribeCallflow();
			break;
		case "PUBLISH":
			callflow = new PublishCallflow();
			break;
		default:
			sendResponse(request.createResponse(500));
			settingsManager.getSipLogger().severe(request, "Routing error. No support for " + request.getMethod()
					+ ". Check application router configuration.");
		}

		return callflow;

	}

}
