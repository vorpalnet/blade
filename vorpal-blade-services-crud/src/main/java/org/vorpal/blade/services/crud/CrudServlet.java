package org.vorpal.blade.services.crud;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.config.Translation;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class CrudServlet extends B2buaServlet {

	public SettingsManager<CrudConfiguration> settingsManager;

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
				RuleSet ruleSet = (RuleSet) t.getAttributes().get("ruleSet");

				if (ruleSet != null) {
					ruleSet.process(inboundRequest);
					callflow = new CrudInitialInvite(this, ruleSet.output);
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
		SettingsManager.sipLogger.warning(outboundRequest, "Sending INVITE...\n" + outboundRequest.toString());
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
