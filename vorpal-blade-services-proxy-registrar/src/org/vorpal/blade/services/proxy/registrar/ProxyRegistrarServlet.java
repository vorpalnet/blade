package org.vorpal.blade.services.proxy.registrar;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.framework.proxy.ProxyInvite;
import org.vorpal.blade.framework.proxy.ProxyListener;
import org.vorpal.blade.framework.proxy.ProxyPlan;
import org.vorpal.blade.framework.proxy.ProxyServlet;
import org.vorpal.blade.framework.proxy.ProxyTier;

/**
 * This class implements an example B2BUA with transfer capabilities.
 * 
 * @author Jeff McDonald
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRegistrarServlet extends ProxyServlet
		implements ProxyListener, SipServletListener, SipApplicationSessionListener {

	private static final long serialVersionUID = 1L;
	public static SettingsManager<ProxyRegistrarSettings> settingsManager;
	public static Logger sipLogger;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest req) {
		String key = getAccountName(req.getTo());

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("Returning sessionKey: " + key);
		}

		return key;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new SettingsManager<ProxyRegistrarSettings>(event, ProxyRegistrarSettings.class, new ProxyRegistrarSettings());
		sipLogger = SettingsManager.getSipLogger();

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(
					"ProxyRegistrarServlet.servletCreated... " + event.getServletContext().getServletContextName());
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(
					"ProxyRegistrarServlet.servletDestroyed... " + event.getServletContext().getServletContextName());
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		sipLogger.warning(request, "ProxyRegistrarServlet.chooseCallflow... "+request.getMethod());

		Callflow callflow = null;
		ProxyRegistrarSettings settings = settingsManager.getCurrent();

		switch (request.getMethod()) {
		case "REGISTER":
			callflow = new RegisterCallflow(settings);
			break;
		case "INVITE":
			callflow = new ProxyInvite(this, null);
			break;
		default:
			callflow = new ProxyCallflow(this, null, false);
		}

		return callflow;
	}

	@Override
	public void proxyRequest(SipServletRequest request, ProxyPlan proxyPlan) throws ServletException, IOException {
		sipLogger.finer(request, "Begin ProxyRegistrarServlet.proxyRequest... ProxyPlan tiers: "+proxyPlan.getTiers().size());

		// Get the default config
		ProxyRegistrarSettings config = ProxyRegistrarServlet.settingsManager.getCurrent();
		
		// Apply config values to 'proxy' object
		config.apply(request.getProxy());
		
		SipApplicationSession appSession = request.getApplicationSession();
		ProxyRegistrar proxyRegistrar = (ProxyRegistrar) appSession.getAttribute("PROXY_REGISTRAR");

		if (proxyRegistrar == null) {
			if (true == config.proxyOnUnregistered) {
				ProxyTier proxyTier = new ProxyTier();
				URI to = request.getAddressHeader("To").getURI();
				proxyTier.getEndpoints().add(to);
				proxyPlan.getTiers().add(proxyTier);
			} else {
				this.sendResponse(request.createResponse(404)); // not found
			}
		} else {
			ProxyTier proxyTier = new ProxyTier();
			proxyTier.setEndpoints(proxyRegistrar.getURIContacts());
			proxyPlan.getTiers().add(proxyTier);
		}
		sipLogger.finer(request, "End ProxyRegistrarServlet.proxyRequest... ProxyPlan tiers: "+proxyPlan.getTiers().size());
	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyPlan ProxyPlan) throws ServletException, IOException {
		sipLogger.finer(response, "ProxyRegistrarServlet.proxyResponse...");

	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(), "ProxyRegistrarServlet.sessionCreated... ");
	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(), "ProxyRegistrarServlet.sessionDestroyed...");
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(), "ProxyRegistrarServlet.sessionExpired...");
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		sipLogger.finer(event.getApplicationSession(), "ProxyRegistrarServlet.sessionReadyToInvalidate...");
	}

}
