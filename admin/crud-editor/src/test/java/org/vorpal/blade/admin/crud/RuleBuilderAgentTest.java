package org.vorpal.blade.admin.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.ai.AiSettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The rule builder's tools, run as the model would call them, with no model:
/// a preview shows what a candidate does, a proposal with a problem is
/// refused, numbers reach the model masked and come back real, and secrets
/// never reach it.
class RuleBuilderAgentTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String INVITE = "INVITE sip:18005551234@example.com SIP/2.0\r\n"
			+ "Via: SIP/2.0/UDP 10.0.0.1;branch=z9hG4bK1\r\nFrom: <sip:alice@example.com>;tag=1\r\n"
			+ "To: <sip:18005551234@example.com>\r\nCall-ID: a1\r\nCSeq: 1 INVITE\r\nMax-Forwards: 70\r\n"
			+ "Content-Length: 0\r\n\r\n";

	@BeforeEach
	void detached() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	private static RuleBuilderAgent agent(String draftJson) throws Exception {
		AiSettings s = new AiSettings();
		s.setEnabled(true);
		s.setApiKey("{CLEARTEXT}test");
		RuleBuilderAgent.Request r = new RuleBuilderAgent.Request();
		r.app = "agenttest";
		r.ruleSet = "lab";
		r.instruction = "tag calls to 18005551234";
		r.message = INVITE;
		r.lifecycleEvent = "send";
		r.draft = MAPPER.readTree(draftJson.replace('\'', '"'));
		return new RuleBuilderAgent(s, r, MAPPER.createObjectNode(), null);
	}

	private static JsonNode json(String s) throws Exception {
		return MAPPER.readTree(s.replace('\'', '"'));
	}

	@Test
	void aPreviewRunsTheCandidateOnTheMaskedSample() throws Exception {
		RuleBuilderAgent a = agent("{'draftRuleSets':{'lab':{'rules':{}}}}");
		JsonNode out = MAPPER.readTree(a.call("preview", json("{'ruleId':'tag','rule':{'method':'INVITE',"
				+ "'operations':[{'type':'create','attribute':'X-Tag','value':'vip'}]}}")));
		assertEquals("tag", out.path("fired").path(0).asText(), out.toString());
		assertTrue(out.path("output").asText().contains("X-Tag: vip"), out.toString());
		// The model sees a stand-in, never the caller's number.
		assertFalse(out.path("output").asText().contains("18005551234"), out.toString());
		assertEquals(0, out.path("problems").size(), out.toString());
	}

	@Test
	void aProposalWithAProblemIsRefusedAndAGoodOneComesBackWithTheRealNumber() throws Exception {
		RuleBuilderAgent a = agent("{'draftRuleSets':{'lab':{'rules':{}}}}");
		Exception refused = assertThrows(IllegalArgumentException.class, () -> a.call("propose",
				json("{'ruleId':'bad','rule':{'operations':[{'type':'reject','status':200}]},'summary':'x'}")));
		assertTrue(refused.getMessage().startsWith("not accepted"), refused.getMessage());
		// The model wrote the stand-in it was shown; the operator gets the real number.
		String standIn = new org.vorpal.blade.framework.v3.ai.NumberMask().mask("18005551234");
		a.call("propose", json("{'ruleId':'tag','rule':{'when':'${To.user} == " + standIn + "','operations':[{'type':'create',"
				+ "'attribute':'X-Tag','value':'vip'}]},'summary':'Tags calls to " + standIn + ".'}"));
		RuleBuilderAgent.Result r = proposal(a);
		assertEquals("tag", r.ruleId);
		assertTrue(r.rule.path("when").asText().endsWith("18005551234"), r.rule.toString());
		assertTrue(r.summary.contains("18005551234"), r.summary);
	}

	@Test
	void aPreviewRunsThePipelineSoAWhenOnItsVariableFires() throws Exception {
		// The rig's first reject draft: crudlab's pipeline sets toUser, and a
		// preview without the pipeline never fired `${toUser} == <number>`.
		RuleBuilderAgent a = agent("{'draftRuleSets':{'lab':{'rules':{}}},'draftPipeline':{"
				+ "'sip':{'type':'sip','selectors':[{'type':'regex','id':'toUser','attribute':'To',"
				+ "'pattern':'^[^<]*<?sips?:(?<user>[^@;>]+)@.*$','expression':'${user}'}]},"
				+ "'crm':{'type':'rest','url':'http://crm.example_co.internal/x','selectors':[]}}}");
		String standIn = new org.vorpal.blade.framework.v3.ai.NumberMask().mask("18005551234");
		JsonNode out = MAPPER.readTree(a.call("preview", json("{'ruleId':'decline','rule':{'method':'INVITE',"
				+ "'when':'${toUser} == " + standIn + "','operations':[{'type':'reject','status':603}]}}")));
		assertEquals("decline", out.path("fired").path(0).asText(), out.toString());
		assertEquals(standIn, out.path("variables").path("toUser").asText(), out.toString());
		// The REST lookup is not made on the model's behalf, and it is told so.
		assertEquals("crm", out.path("pipelineSkipped").path(0).asText(), out.toString());
	}

	@Test
	void aProposalMayNameAnotherRuleSet() throws Exception {
		// One conversation for the page: the model may put a rule in any set.
		RuleBuilderAgent a = agent("{'draftRuleSets':{'lab':{'rules':{}},'night':{'rules':{}}}}");
		a.call("propose", json("{'ruleSet':'night','ruleId':'tag','rule':{'method':'INVITE','operations':[{'type':'create',"
				+ "'attribute':'X-Night','value':'1'}]},'summary':'Tags night calls.'}"));
		RuleBuilderAgent.Result r = proposal(a);
		assertEquals("night", r.ruleSet);
		assertEquals("tag", r.ruleId);
	}

	@Test
	void secretsInTheDraftAreRedacted() throws Exception {
		JsonNode d = json("{'draftPipeline':{'rest':{'type':'rest','password':'{AES}abc','url':'http://x'}}}");
		RuleBuilderAgent.redact(d);
		assertEquals(RuleBuilderAgent.REDACTED, d.path("draftPipeline").path("rest").path("password").asText());
		assertEquals("http://x", d.path("draftPipeline").path("rest").path("url").asText());
	}

	@Test
	void theReferenceIsThePackageDocumentation() throws Exception {
		String ref = RuleBuilderAgent.reference();
		assertTrue(ref.contains("## Operation reference"), ref.substring(0, Math.min(200, ref.length())));
		assertFalse(ref.contains("///"));
	}

	@Test
	void toolsAreDeclared() {
		assertEquals(java.util.Arrays.asList("preview", "run_tests", "propose"),
				RuleBuilderAgent.tools().stream().map(t -> t.name()).collect(java.util.stream.Collectors.toList()));
	}

	private static RuleBuilderAgent.Result proposal(RuleBuilderAgent a) throws Exception {
		java.lang.reflect.Field f = RuleBuilderAgent.class.getDeclaredField("proposal");
		f.setAccessible(true);
		return (RuleBuilderAgent.Result) f.get(a);
	}
}
