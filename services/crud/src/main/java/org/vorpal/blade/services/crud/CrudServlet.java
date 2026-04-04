package org.vorpal.blade.services.crud;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;

/**
 * B2BUA servlet that applies configurable CRUD rules to SIP messages
 * at every lifecycle point. Uses standard framework callflows — rule
 * application happens in the B2BUA lifecycle callbacks.
 */
@WebListener
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class CrudServlet extends B2buaServlet {

	private static final String RULESET_ATTR = "crud.ruleSet";
	public static SettingsManager<CrudConfiguration> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, CrudConfiguration.class, new CrudConfigurationSample());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settingsManager.unregister();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		CrudConfiguration settings = settingsManager.getCurrent();

		if (inboundRequest.isInitial()) {
			Translation t = settings.findTranslation(inboundRequest);
			if (t != null) {
				String ruleSetId = (String) t.getAttribute("ruleSet");
				if (ruleSetId != null) {
					RuleSet ruleSet = settings.getRuleSets().get(ruleSetId);
					if (ruleSet != null) {
						inboundRequest.getApplicationSession().setAttribute(RULESET_ATTR, ruleSet);
					}
				}
			}
		}

		return super.chooseCallflow(inboundRequest);
	}

	private void applyRules(javax.servlet.sip.SipServletMessage msg, String lifecycleEvent) {
		RuleSet ruleSet = (RuleSet) msg.getApplicationSession().getAttribute(RULESET_ATTR);
		if (ruleSet != null) {
			ruleSet.applyRules(msg, lifecycleEvent);
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callStarted");
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		applyRules(outboundResponse, "callAnswered");
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callConnected");
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callCompleted");
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		applyRules(outboundResponse, "callDeclined");
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callAbandoned");
	}

	@Override
	public void requestEvent(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "requestEvent");
	}

	@Override
	public void responseEvent(SipServletResponse outboundResponse) throws ServletException, IOException {
		applyRules(outboundResponse, "responseEvent");
	}
}
