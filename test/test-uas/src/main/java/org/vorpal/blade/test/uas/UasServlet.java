package org.vorpal.blade.test.uas;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.crud.KeepOnlyPartOperation;
import org.vorpal.blade.framework.v3.crud.Rule;
import org.vorpal.blade.framework.v3.tester.Scenario;
import org.vorpal.blade.framework.v3.tester.TesterConfiguration;
import org.vorpal.blade.framework.v3.tester.TesterServlet;
import org.vorpal.blade.test.uas.config.TestUasConfig;
import org.vorpal.blade.test.uas.config.TestUasConfigSample;

/// SIP test server — the terminator end of the BLADE test pair. All
/// behavior comes from the framework [TesterServlet] scenario machinery:
///
/// - `scenario=` Request-URI parameter, translation-plan matches, and the
///   classic `status` / `delay` / `refer` shorthands select per-call
///   behavior (answer locally, transfer, or transform-and-forward)
/// - configured scenarios live in `test-uas.json`, edited in the
///   Configurator
///
/// When nothing selects a scenario, the built-in default forwards the call
/// B2BUA-style after stripping a multipart (e.g. SIPREC) body down to its
/// `application/sdp` part — softphones choke on multipart, so the
/// downstream endpoint sees a clean single-part INVITE. A configured
/// `defaultScenario` overrides this.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UasServlet extends TesterServlet {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<TestUasConfig> settingsManager;

	/// Built-in default behavior: B2BUA passthrough with multipart → SDP
	/// stripping. Built once; rule application is stateless, so sharing the
	/// instance across calls is safe (same as config-loaded scenarios).
	private static final Scenario STRIP_TO_SDP = buildStripScenario();

	private static Scenario buildStripScenario() {
		Scenario scenario = new Scenario();
		scenario.setDescription("Forward the call; strip multipart (SIPREC) bodies down to bare SDP");
		scenario.setRole(Scenario.ROLE_B2BUA);

		Rule rule = new Rule();
		rule.setId("strip-to-sdp");
		rule.setMethod("INVITE");
		rule.setMessageType("request");
		rule.setEvent("callStarted");
		rule.getOperations().add(new KeepOnlyPartOperation("application/sdp"));
		scenario.getRules().add(rule);

		return scenario;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, TestUasConfig.class, new TestUasConfigSample());
		initTester(event, settingsManager.getServletContextName());
		sipLogger.logConfiguration(settingsManager.getCurrent());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		destroyTester();
		try {
			settingsManager.unregister();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	@Override
	protected TesterConfiguration testerConfiguration() {
		return settingsManager.getCurrent();
	}

	@Override
	protected Scenario defaultScenario(SipServletRequest request) {
		return STRIP_TO_SDP;
	}
}
