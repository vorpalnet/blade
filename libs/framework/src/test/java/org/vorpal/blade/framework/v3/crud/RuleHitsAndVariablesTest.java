package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.servlet.sip.SipServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.metrics.Counter;
import org.vorpal.blade.framework.v3.metrics.MetricsRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// A rule that matches is counted in the application's metrics, one that is
/// switched off is not; the file's `variables` are what a call starts with.
class RuleHitsAndVariablesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String INVITE = "INVITE sip:bob@example.com SIP/2.0\r\n"
			+ "Via: SIP/2.0/UDP 10.0.0.1;branch=z9hG4bK1\r\nFrom: <sip:alice@example.com>;tag=1\r\n"
			+ "To: <sip:bob@example.com>\r\nCall-ID: h1\r\nCSeq: 1 INVITE\r\nMax-Forwards: 70\r\n"
			+ "Content-Length: 0\r\n\r\n";

	private MetricsRegistry registry;

	@BeforeEach
	void detached() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
		registry = MetricsRegistry.forApp("hits-test");
		MetricsRegistry.bind("hits-test", registry);
	}

	@AfterEach
	void unbind() {
		MetricsRegistry.unbind("hits-test");
	}

	private static Crud crud(String json) throws Exception {
		return MAPPER.readValue(json.replace('\'', '"'), Crud.class);
	}

	@Test
	void matchingRulesAreCountedAndRulesSwitchedOffAreNot() throws Exception {
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'on':{'method':'INVITE'},'off':{'enabled':false},'bye':{'method':'BYE'}}}}}");
		RuleSet rs = c.getRuleSets().get("rs");
		for (int i = 0; i < 3; i++) {
			rs.applyRules((SipServletRequest) SipMessageParser.parse(INVITE), Crud.SEND_EVENT);
		}
		assertEquals(3, registry.findCounter(RuleSet.HITS + "rs/on").total());
		assertNull(registry.findCounter(RuleSet.HITS + "rs/off"));
		assertNull(registry.findCounter(RuleSet.HITS + "rs/bye"));
	}

	@Test
	void theFilesVariablesResolveWithoutBeingStored() throws Exception {
		Crud c = crud("{'variables':{'carrier':'sbc1','userAgent':'polycom'}}");
		CrudHook.useVariables(c.getVariables());
		try {
			SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE);
			request.getSession().setAttribute("userAgent", "caller");
			Crud.enrich(c.getPipeline(), c.getVariables(), request);
			assertEquals("sbc1", MessageHelper.getSessionVariables(request.getApplicationSession()).get("carrier"));
			// Nothing written: a variable named like a session attribute leaves it alone.
			assertEquals("caller", request.getSession().getAttribute("userAgent"));
			assertNull(request.getApplicationSession().getAttribute(MessageHelper.VARIABLE_PREFIX + "carrier"));
		} finally {
			CrudHook.useVariables(null);
		}
	}

	@Test
	void onlyWhatThePipelineWroteIsStored() throws Exception {
		// A table writes ruleSet=lab; the file's variable is also lab. It is
		// still stored: the connector wrote it.
		Crud c = crud("{'variables':{'ruleSet':'lab'},'pipeline':{'pick':{'type':'table','tables':[{'match':'hash','keyExpression':'x','translations':{'x':{'attributes':{'ruleSet':'lab'}}}}]}}}");
		SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE);
		request.getApplicationSession().setAttribute("appOwn", "value");
		Crud.enrich(c.getPipeline(), c.getVariables(), request);
		assertNull(request.getApplicationSession().getAttribute(MessageHelper.VARIABLE_PREFIX + "appOwn"));
	}

	@Test
	void nullRemovesAnInheritedRuleConnectorOrVariable() throws Exception {
		JsonNode domain = MAPPER.readTree("{'variables':{'a':'1','b':'2'},'pipeline':{'sip':{'type':'sip'}},'ruleSets':{'rs':{'rules':{'keep':{},'drop':{}}}}}".replace('\'', '"'));
		JsonNode server = MAPPER.readTree("{'variables':{'b':null},'pipeline':{'sip':null},'ruleSets':{'rs':{'rules':{'drop':null}}}}".replace('\'', '"'));
		Crud c = MAPPER.treeToValue(org.vorpal.blade.framework.v3.configuration.ConfigFiles.merge(java.util.Arrays.asList(domain, server), MAPPER), Crud.class);
		assertEquals(java.util.Set.of("a"), c.getVariables().keySet());
		assertEquals(0, c.getPipeline().size());
		assertEquals(java.util.Set.of("keep"), c.getRuleSets().get("rs").getRules().keySet());
	}

	@Test
	void aReceivedMessageRunsOnlyRulesThatNameReceive() throws Exception {
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'neg':{'event':'!callCompleted'},'named':{'event':'receive'}}}}}");
		SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE);
		assertEquals(false, c.getRuleSets().get("rs").getRules().get("neg").matches(request, Crud.RECEIVE_EVENT));
		assertEquals(true, c.getRuleSets().get("rs").getRules().get("named").matches(request, Crud.RECEIVE_EVENT));
	}

	@Test
	void aRuleThatIsUndoneIsNotCounted() throws Exception {
		// status on a request fails, so the rule is undone.
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'bad':{'operations':[{'type':'create','attribute':'status','value':'480'}]}}}}}");
		c.getRuleSets().get("rs").applyRules((SipServletRequest) SipMessageParser.parse(INVITE), Crud.SEND_EVENT);
		assertNull(registry.findCounter(RuleSet.HITS + "rs/bad"));
	}

	@Test
	void aJsonPathThatFindsNothingDoesNotUndoTheRule() throws Exception {
		String body = "{\"a\":1}";
		SipServletRequest msg = (SipServletRequest) SipMessageParser.parse("MESSAGE sip:b@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK4\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
				+ "Call-ID: j2\r\nCSeq: 1 MESSAGE\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
				+ "\r\n\r\n" + body);
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'r':{'operations':[{'type':'create','attribute':'X-Mark','value':'1'},"
				+ "{'type':'jsonRead','expressions':{'missing':'$.nope.deeper'}}]}}}}}");
		c.getRuleSets().get("rs").applyRules(msg, Crud.SEND_EVENT);
		assertEquals("1", msg.getHeader("X-Mark"));
	}

	@Test
	void anXPathOnABodyThatIsNotXmlDoesNotUndoTheRule() throws Exception {
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'r':{'operations':[{'type':'create','attribute':'X-Mark','value':'1'},"
				+ "{'type':'xmlRead','expressions':{'x':'//a'}}]}}}}}");
		SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE.replace("Content-Length: 0\r\n\r\n",
				"Content-Type: application/sdp\r\nContent-Length: 4\r\n\r\nv=0\n"));
		c.getRuleSets().get("rs").applyRules(request, Crud.SEND_EVENT);
		assertEquals("1", request.getHeader("X-Mark"));
	}

	@Test
	void aLegNamedOnTheMessageWins() throws Exception {
		SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE);
		request.getSession().setAttribute("userAgent", "caller");
		assertEquals("caller", Rule.legOf(request));
		request.setAttribute(Rule.LEG_ATTR, "callee");
		assertEquals("callee", Rule.legOf(request));
	}

	@Test
	void updateWithEveryMatchAndNoReplacementDeletesTheMatches() throws Exception {
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'strip':{'operations':[{'type':'update','attribute':'To','pattern':'@example[.]com','everyMatch':true}]}}}}}");
		SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE);
		c.getRuleSets().get("rs").applyRules(request, Crud.SEND_EVENT);
		assertEquals("<sip:bob>", request.getHeader("To"));
	}

	@Test
	void enrichingTwiceNestsNothing() throws Exception {
		Crud c = crud("{'variables':{'carrier':'sbc1'}}");
		SipServletRequest request = (SipServletRequest) SipMessageParser.parse(INVITE);
		MessageHelper.putVariable(request.getApplicationSession(), "earlier", "x");
		Crud.enrich(c.getPipeline(), c.getVariables(), request);
		for (String name : request.getApplicationSession().getAttributeNameSet()) {
			org.junit.jupiter.api.Assertions.assertFalse(name.startsWith(MessageHelper.VARIABLE_PREFIX + MessageHelper.VARIABLE_PREFIX), name);
		}
		assertEquals("x", MessageHelper.getSessionVariables(request.getApplicationSession()).get("earlier"));
	}

	@Test
	void thePreviewResolvesTheFilesVariablesBeneathTheCallAsTheEnginesDo() throws Exception {
		// The reset clears the call's own tenant; the file's value is what
		// ${tenant} then reads, in the preview as on an engine.
		CrudConfiguration config = MAPPER.readValue(("{'variables':{'tenant':'default'},"
				+ "'ruleSets':{'rs':{'rules':{'r':{'method':'INVITE','resetVariables':true,'operations':["
				+ "{'type':'read','attribute':'From','pattern':'sip:(?<tenant>[0-9]+)@.*'},"
				+ "{'type':'create','attribute':'X-Tenant','value':'${tenant}'}]}}}}}").replace('\'', '"'),
				CrudConfiguration.class);
		PreviewEngine.PreviewResult r = PreviewEngine.preview(config, "rs", INVITE, Crud.SEND_EVENT,
				java.util.Map.of("tenant", "from-the-pipeline"), null, null);
		assertNull(r.error, r.error);
		org.junit.jupiter.api.Assertions.assertTrue(r.output.contains("X-Tenant: default"), r.output);
	}

	@Test
	void theReceiveEventIsReadWithoutCase() {
		org.junit.jupiter.api.Assertions.assertTrue(Rule.namesReceive("Receive"));
		org.junit.jupiter.api.Assertions.assertTrue(Rule.receiveOnly(" RECEIVE "));
	}

	@Test
	void anUnknownMessageTypeStillMatchesBothAndSaveAsksForItToGo() throws Exception {
		Crud c = crud("{'ruleSets':{'rs':{'rules':{'any':{'messageType':'any','method':'INVITE'}}}}}");
		Rule r = c.getRuleSets().get("rs").getRules().get("any");
		org.junit.jupiter.api.Assertions.assertTrue(r.matches(SipMessageParser.parse(INVITE), Crud.SEND_EVENT));
		org.junit.jupiter.api.Assertions.assertTrue(r.problems().stream().anyMatch(p -> p.startsWith("messageType:")), r.problems().toString());
	}

	@Test
	void pseudoAttributesAreReadInAnyCase() {
		assertEquals("status", MessageHelper.canonical("Status"));
		assertEquals("reasonPhrase", MessageHelper.canonical("REASONPHRASE"));
		assertEquals("requestURI", MessageHelper.canonical("RequestUri"));
		assertEquals("X-Custom", MessageHelper.canonical("X-Custom"));
	}
}
