package org.vorpal.blade.test.uac;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.tester.Assertion;
import org.vorpal.blade.framework.v3.tester.ResponseScript;
import org.vorpal.blade.framework.v3.tester.Scenario;

/// Sample configuration written to `config/custom/vorpal/test-uac.json.SAMPLE`
/// and used to generate the JSON Schema the Configurator renders.
///
/// Demonstrates the scenario layer on the originate side: a basic load
/// scenario with latency/status assertions, and a SIPREC scenario whose
/// template (copy `samples/invite-template-siprec.txt` →
/// `_templates/invite-template-siprec.txt`) attaches the
/// `application/rs-metadata+xml` part to every generated INVITE. Start a run
/// with `POST /test-uac/api/v1/loadtest/start {"scenario": "load-basic"}` —
/// or from the BLADE Test Console.
public class UserAgentClientConfigSample extends UserAgentClientConfig {
	private static final long serialVersionUID = 1L;

	public UserAgentClientConfigSample() {
		this.about.setName("Test UAC")
				.setTagline("Scenario-driven SIP load generator")
				.setDescription("Originates SIP calls at scale — CPS or concurrent-call pacing, per node. "
						+ "Scenarios script the INVITE (templates, header/body transformation rules), what "
						+ "responses to expect, and per-call assertions; metrics report latency percentiles, "
						+ "status distributions, and pass/fail counts. As a B2BUA it can also transform real "
						+ "softphone calls, e.g. simulating a SIPREC recorder leg. No SIPp required.");

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParametersDefault();
		this.session.setExpiration(180);

		this.getOriginate().setScenario("load-basic");
		this.getOriginate().setFromAddressPattern("sip:load-${index}@blade.test");
		this.getOriginate().setToAddressPattern("sip:target@uas.test");
		this.getOriginate().setRequestUriTemplate("sip:target@uas.test");
		this.getOriginate().setDuration("30s");
		this.getOriginate().getLoad().setMode("cps");
		this.getOriginate().getLoad().setTargetCps(10.0);

		// --- originate: basic load with assertions ---
		Scenario basic = new Scenario();
		basic.setDescription("Plain load calls; expect a 2xx within half a second");
		basic.setRole(Scenario.ROLE_ORIGINATE);
		ResponseScript basicScript = new ResponseScript();
		basicScript.setExpectFinal("2xx");
		basic.setResponseScript(basicScript);
		basic.getAssertions().add(new Assertion("answered", "${lastStatus} >= 200 && ${lastStatus} < 300"));
		Assertion fast = new Assertion("fast-setup", "${setupMs} < 500");
		fast.setOnFail(Assertion.ON_FAIL_WARN);
		basic.getAssertions().add(fast);
		this.getScenarios().put("load-basic", basic);

		// --- originate: simulate a SIPREC recorder leg ---
		Scenario siprec = new Scenario();
		siprec.setDescription("Load calls carrying a SIPREC multipart body (rs-metadata + SDP from the template)");
		siprec.setRole(Scenario.ROLE_ORIGINATE);
		siprec.setTemplate("invite-template-siprec.txt");
		ResponseScript siprecScript = new ResponseScript();
		siprecScript.setExpectFinal("2xx");
		siprec.setResponseScript(siprecScript);
		this.getScenarios().put("siprec-originate", siprec);

		// --- b2bua: dress up real softphone calls as SIPREC ---
		Scenario dressUp = new Scenario();
		dressUp.setDescription("Forward softphone calls with the SIPREC template merged in "
				+ "(template SDP wins; softphone SDP is preserved when the template has none)");
		dressUp.setRole(Scenario.ROLE_B2BUA);
		dressUp.setTemplate("invite-template-siprec.txt");
		this.getScenarios().put("siprec-b2bua", dressUp);
		// Make it the default for inbound softphone calls by uncommenting:
		// this.setDefaultScenario("siprec-b2bua");
	}
}
