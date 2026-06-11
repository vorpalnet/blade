package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.crud.KeepOnlyPartOperation;
import org.vorpal.blade.framework.v3.crud.Rule;
import org.vorpal.blade.framework.v3.crud.RuleSet;
import org.vorpal.blade.framework.v3.tester.ResponseScript;
import org.vorpal.blade.framework.v3.tester.ResponseStep;
import org.vorpal.blade.framework.v3.tester.Scenario;

/// Sample configuration written to `config/custom/vorpal/test-uas.json.SAMPLE`
/// and used to generate the JSON Schema the Configurator renders.
///
/// Besides the inherited logging/session defaults and the display metadata
/// (`about`), this demonstrates the scenario layer: a scripted-rejection
/// endpoint, a ringing-then-answer endpoint with auto-teardown, a transfer,
/// and the explicit form of the built-in strip-multipart B2BUA default.
/// Select any of them per call with `;scenario=<name>` on the Request-URI —
/// or keep using the classic `;status=486`, `;delay=5s`, `;refer=sip:...`
/// shorthands, which still work with no configuration at all.
public class TestUasConfigSample extends TestUasConfig {
	private static final long serialVersionUID = 1L;

	public TestUasConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParameters();
		this.session.setExpiration(60);

		// --- answer: scripted rejection ---
		Scenario busy = new Scenario();
		busy.setDescription("Ring briefly, then reject with 486 Busy Here");
		busy.setRole(Scenario.ROLE_ANSWER);
		ResponseScript busyScript = new ResponseScript();
		busyScript.getSend().add(new ResponseStep(180, "200ms"));
		busyScript.getSend().add(new ResponseStep(486, "1s"));
		busy.setResponseScript(busyScript);
		this.getScenarios().put("answer-486", busy);

		// --- answer: ring, answer, hold for 30s, hang up ---
		Scenario answer = new Scenario();
		answer.setDescription("Ring, answer with a hold SDP, tear down after 30s");
		answer.setRole(Scenario.ROLE_ANSWER);
		ResponseScript answerScript = new ResponseScript();
		answerScript.getSend().add(new ResponseStep(180, "200ms"));
		answerScript.getSend().add(new ResponseStep(200, "500ms"));
		answerScript.setAutoByeAfter("30s");
		answer.setResponseScript(answerScript);
		this.getScenarios().put("answer-hold-30s", answer);

		// --- answer: transfer ---
		Scenario transfer = new Scenario();
		transfer.setDescription("Answer, then REFER the caller to a transfer target");
		transfer.setRole(Scenario.ROLE_ANSWER);
		ResponseScript transferScript = new ResponseScript();
		transferScript.getSend().add(new ResponseStep(200));
		transferScript.setRefer("sip:transfer-target@uas.test");
		transferScript.setReferStatus("200");
		transfer.setResponseScript(transferScript);
		this.getScenarios().put("answer-transfer", transfer);

		// --- b2bua: the built-in default, spelled out ---
		RuleSet strip = new RuleSet();
		strip.setId("strip-to-sdp");
		strip.setDescription("Keep only the application/sdp part of a multipart body");
		Rule stripRule = new Rule();
		stripRule.setId("strip");
		stripRule.setMethod("INVITE");
		stripRule.setMessageType("request");
		stripRule.setEvent("callStarted");
		stripRule.getOperations().add(new KeepOnlyPartOperation("application/sdp"));
		strip.getRules().add(stripRule);
		this.getRuleSets().put(strip.getId(), strip);

		Scenario passthrough = new Scenario();
		passthrough.setDescription("Forward the call, stripping SIPREC multipart down to bare SDP");
		passthrough.setRole(Scenario.ROLE_B2BUA);
		passthrough.setRuleSet("strip-to-sdp");
		this.getScenarios().put("strip-siprec-b2bua", passthrough);
		// Uncommenting defaultScenario makes the built-in behavior explicit:
		// this.setDefaultScenario("strip-siprec-b2bua");
	}
}
