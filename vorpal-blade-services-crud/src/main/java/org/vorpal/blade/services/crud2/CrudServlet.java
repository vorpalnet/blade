package org.vorpal.blade.services.crud2;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.services.crud2.config.CrudConfiguration;
import org.vorpal.blade.services.crud2.config.CrudConfigurationSample;

//@WebListener
//@javax.servlet.sip.annotation.SipApplication(distributable = true)
//@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
//@javax.servlet.sip.annotation.SipListener
public class CrudServlet extends B2buaServlet {

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
			e.printStackTrace();
		}

	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		Callflow callflow = null;

		CrudConfiguration settings = settingsManager.getCurrent();

		if (inboundRequest.getMethod().equals("INVITE") && inboundRequest.isInitial()) {
			Translation t = settings.findTranslation(inboundRequest);
			if (t != null) {
				String ruleSetId = (String) t.getAttribute("ruleSet");
				if (ruleSetId != null) {
					org.vorpal.blade.services.crud.RuleSet ruleSet = settings.ruleSets.get(ruleSetId);
					if (ruleSet != null) {
						
						
						
						ruleSet.process(inboundRequest);
						callflow = new CrudInitialInvite(this, ruleSet.map);
					}
				}
			}
		}

		if (callflow == null) {
			callflow = super.chooseCallflow(inboundRequest);
		}

		return callflow;
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
//		sipLogger.warning(outboundRequest, "callStarted... \n" + outboundRequest);
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {

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
