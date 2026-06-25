// Gumball Agent — proto. MIT License, (c) 2026 Vorpal Networks, LLC.
package org.vorpal.blade.proto.gumball.agent;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * Routes the initial INVITE to {@link AgentCallflow}. In-dialog ACK/BYE/re-INVITE are handled
 * by {@code AsyncSipServlet} and the registered callflow continuations. Mirrors the
 * {@code OptionsSipServlet} lifecycle (SettingsManager in servletCreated, unregister in destroy).
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class AgentSipServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<AgentSettings> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settingsManager = new SettingsManager<>(event, AgentSettings.class, new AgentSettingsSample());
		} catch (Exception ex) {
			if (sipLogger != null) {
				sipLogger.severe("Gumball: unable to load gumball-agent.json configuration file.");
				sipLogger.severe(ex);
			} else {
				ex.printStackTrace();
			}
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settingsManager.unregister();
		} catch (Exception e) {
			// do nothing
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		if ("INVITE".equals(request.getMethod()) && request.isInitial()) {
			return new AgentCallflow();
		}
		return null; // AsyncSipServlet logs + handles unrouted methods (no NPE)
	}

}
