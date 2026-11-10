package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// The preview places a pasted message in a call, and reports what the
/// rules would do on the wire: every occurrence of a repeated header, the
/// leg and SDP role the filters see, a status rewrite, a refusal, and a rule
/// that was undone.
class PreviewCallTest {

	private static final String SDP = "v=0\r\no=- 1 1 IN IP4 192.0.2.1\r\ns=-\r\nc=IN IP4 192.0.2.1\r\nt=0 0\r\n"
			+ "m=audio 10000 RTP/AVP 0\r\n";

	private static final String INVITE = "INVITE sip:bob@example.com SIP/2.0\r\n" //
			+ "From: <sip:alice@example.com>;tag=a1\r\n" //
			+ "To: <sip:bob@example.com>\r\n" //
			+ "CSeq: 1 INVITE\r\n" //
			+ "P-Asserted-Identity: <sip:+15550100@example.com>\r\n" //
			+ "P-Asserted-Identity: <tel:+15550100>\r\n" //
			+ "Content-Type: application/sdp\r\n" //
			+ "\r\n" + SDP;

	private static final String REINVITE = INVITE.replace("To: <sip:bob@example.com>", "To: <sip:bob@example.com>;tag=b1");

	private static final String OK = "SIP/2.0 200 OK\r\n" //
			+ "From: <sip:alice@example.com>;tag=a1\r\n" //
			+ "To: <sip:bob@example.com>;tag=b1\r\n" //
			+ "CSeq: 1 INVITE\r\n" //
			+ "Content-Type: application/sdp\r\n" //
			+ "\r\n" + SDP;

	private static final String BUSY = "SIP/2.0 486 Busy Here\r\n" //
			+ "From: <sip:alice@example.com>;tag=a1\r\n" //
			+ "To: <sip:bob@example.com>;tag=b1\r\n" //
			+ "CSeq: 1 INVITE\r\n" //
			+ "\r\n";

	@BeforeEach
	void wire() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	private static Rule rule(String id, Operation... operations) {
		Rule rule = new Rule();
		rule.setId(id);
		rule.getOperations().addAll(Arrays.asList(operations));
		return rule;
	}

	private static CrudConfiguration config(Rule... rules) {
		RuleSet ruleSet = new RuleSet();
		ruleSet.setId("rs");
		ruleSet.add(rules);
		CrudConfiguration config = new CrudConfiguration();
		config.getRuleSets().put("rs", ruleSet);
		return config;
	}

	private static PreviewEngine.PreviewResult preview(CrudConfiguration config, String wire) {
		return PreviewEngine.preview(config, "rs", wire, null, null, null, null);
	}

	@Test
	void everyOccurrenceOfARepeatedHeaderSurvives() {
		UpdateOperation tel = new UpdateOperation("P-Asserted-Identity", "^(?<pai><tel:[^>]*)>$", "${pai};cpc=ordinary>");
		PreviewEngine.PreviewResult r = preview(config(rule("tel", tel)), INVITE);
		assertTrue(r.output.contains("P-Asserted-Identity: <sip:+15550100@example.com>\r\n"), r.output);
		assertTrue(r.output.contains("P-Asserted-Identity: <tel:+15550100;cpc=ordinary>\r\n"), r.output);
	}

	@Test
	void aSentRequestIsOnTheCalleeLegAndOffers() {
		Rule callee = rule("callee", new CreateOperation("X-Seen", "yes"));
		callee.setLeg(Rule.Leg.callee);
		callee.setSdp(Rule.SdpRole.offer);
		PreviewEngine.PreviewResult r = preview(config(callee), INVITE);
		assertEquals("callee", r.leg);
		assertEquals("offer", r.sdp);
		assertEquals(Arrays.asList("callee"), r.rulesFired);
	}

	@Test
	void aSentResponseIsOnTheCallerLegAndAnswers() {
		Rule caller = rule("caller", new CreateOperation("X-Seen", "yes"));
		caller.setLeg(Rule.Leg.caller);
		caller.setSdp(Rule.SdpRole.answer);
		PreviewEngine.PreviewResult r = preview(config(caller), OK);
		assertEquals("caller", r.leg);
		assertEquals("answer", r.sdp);
		assertEquals(Arrays.asList("caller"), r.rulesFired);
	}

	@Test
	void theReceiveEventTurnsTheLegsAround() {
		Rule rule = rule("in", new CreateOperation("X-In", "yes"));
		rule.setEvent(Crud.RECEIVE_EVENT);
		rule.setLeg(Rule.Leg.caller);
		PreviewEngine.PreviewResult r = PreviewEngine.preview(config(rule), "rs", INVITE, Crud.RECEIVE_EVENT,
				null, null, null);
		assertEquals("caller", r.leg);
		assertEquals(Arrays.asList("in"), r.rulesFired);
	}

	@Test
	void aLateOfferIn200IsAnOffer() {
		PreviewEngine.PreviewResult r = PreviewEngine.preview(config(), "rs", OK, null, null, null, false);
		assertEquals("offer", r.sdp);
	}

	@Test
	void aStatusRewriteShowsOnTheStatusLine() {
		PreviewEngine.PreviewResult r = preview(
				config(rule("busy", new CreateOperation("status", "480"),
						new CreateOperation("reasonPhrase", "Unavailable"))),
				BUSY);
		assertEquals(Arrays.asList("busy"), r.rulesFired);
		assertTrue(r.output.startsWith("SIP/2.0 480 Unavailable\r\n"), r.output);
	}

	@Test
	void aRejectIsReported() {
		PreviewEngine.PreviewResult r = preview(config(rule("no", new RejectOperation(603, "Declined"))), INVITE);
		assertEquals("603 Declined", r.rejected);
	}

	@Test
	void aRejectInADialogIsUndone() {
		PreviewEngine.PreviewResult r = preview(config(rule("no", new RejectOperation(603, "Declined"))), REINVITE);
		assertNull(r.rejected);
		assertEquals(Arrays.asList("no"), r.rulesUndone);
		assertTrue(r.rulesFired.isEmpty());
	}

	@Test
	void anUndoneRuleLeavesNoTrace() {
		Rule atomic = rule("atomic", new CreateOperation("X-Half", "done"), new CreateOperation("status", "480"));
		PreviewEngine.PreviewResult r = preview(config(atomic), INVITE);
		assertEquals(Arrays.asList("atomic"), r.rulesUndone);
		assertTrue(!r.output.contains("X-Half"), r.output);
	}
}
