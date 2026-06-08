package org.vorpal.blade.test.uac;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.tester.Scenario;
import org.vorpal.blade.framework.v3.tester.TesterConfiguration;
import org.vorpal.blade.framework.v3.tester.TesterServlet;

/// SIP test client — the originator end of the BLADE test pair. All
/// behavior comes from the framework [TesterServlet] machinery:
///
/// - the [LoadEngine][org.vorpal.blade.framework.v3.tester.LoadEngine]
///   originates calls at scale (CPS or concurrent pacing), driven by the
///   REST API ([LoadTestAPI]) or the Test Console (federated JMX)
/// - scenarios in `test-uac.json` script the INVITE (templates,
///   transformation rules), expected responses, and per-call assertions
/// - inbound softphone calls pass through B2BUA-style with the resolved
///   scenario's template and rules applied — e.g. dressing a plain call up
///   as a SIPREC recorder leg
///
/// The legacy top-level `template` config field is still honored on the
/// softphone path when the resolved scenario has no template of its own.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UserAgentClientServlet extends TesterServlet {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<UserAgentClientConfig> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, UserAgentClientConfig.class, new UserAgentClientConfigSample());
		initTester(event, settingsManager.getServletContextName());
		sipLogger.info("UserAgentClientServlet.servletCreated");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		sipLogger.info("UserAgentClientServlet.servletDestroyed");
		destroyTester();
		settingsManager.unregister();
	}

	@Override
	protected TesterConfiguration testerConfiguration() {
		return settingsManager.getCurrent();
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		outboundRequest.setAttribute("noKeepAlive", Boolean.TRUE);

		super.callStarted(outboundRequest); // scenario template + rules

		// Legacy: a top-level `template` in test-uac.json applies to every
		// outbound softphone INVITE when the resolved scenario didn't bring
		// its own template.
		UserAgentClientConfig config = settingsManager.getCurrent();
		Scenario scenario = (Scenario) outboundRequest.getApplicationSession().getAttribute(SCENARIO_ATTR);
		if (config.getTemplate() != null && !config.getTemplate().isEmpty()
				&& (scenario == null || scenario.getTemplate() == null)) {
			try {
				templateLoader.get(config.getTemplate()).apply(outboundRequest);
			} catch (Exception e) {
				sipLogger.warning(outboundRequest,
						"UserAgentClientServlet template '" + config.getTemplate() + "' failed: " + e.getMessage());
			}
		}
	}
}
