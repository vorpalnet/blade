package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import javax.servlet.sip.SipServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.Configuration;

/// What a rule can and cannot do to a message: repeated headers, status
/// rewrites, refusals, unset variables, the `leg` and `sdp` filters,
/// all-or-nothing rules, and rules on received messages.
class RuleProcessingTest {

	private static final String SDP = "v=0\r\no=- 1 1 IN IP4 192.0.2.1\r\ns=-\r\nc=IN IP4 192.0.2.1\r\nt=0 0\r\n"
			+ "m=audio 10000 RTP/AVP 0\r\n";

	@BeforeEach
	void wire() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	private static DetachedRequest invite() throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("app");
		DetachedRequest invite = new DetachedRequest(appSession, "INVITE", "<sip:alice@example.com>;tag=a1",
				"<sip:bob@example.com>");
		invite.setSession(new DetachedSipSession(appSession));
		return invite;
	}

	private static Rule rule(Operation... operations) {
		Rule rule = new Rule();
		rule.setId("test");
		rule.getOperations().addAll(Arrays.asList(operations));
		return rule;
	}

	private static List<String> values(DetachedRequest msg, String name) {
		return MessageHelper.getHeaderValues(msg, name);
	}

	@Nested
	class RepeatedHeaders {

		DetachedRequest invite;

		@BeforeEach
		void twoIdentities() throws Exception {
			invite = invite();
			invite.addHeader("P-Asserted-Identity", "<sip:+15550100@example.com>");
			invite.addHeader("P-Asserted-Identity", "<tel:+15550100>");
		}

		@Test
		void readTakesTheFirstOccurrenceThatMatches() throws Exception {
			rule(new ReadOperation("P-Asserted-Identity", "<tel:(?<tel>[^>]+)>")).process(invite);
			assertEquals("+15550100", MessageHelper.getVariable(invite.getApplicationSession(), "tel"));
		}

		@Test
		void updateChangesOnlyTheOccurrencesItMatches() throws Exception {
			rule(new UpdateOperation("P-Asserted-Identity", "^<tel:(?<n>.*)>$", "<tel:${n};cpc=ordinary>"))
					.process(invite);
			assertEquals(List.of("<sip:+15550100@example.com>", "<tel:+15550100;cpc=ordinary>"),
					values(invite, "P-Asserted-Identity"));
		}

		@Test
		void createAddsAnotherOccurrence() throws Exception {
			rule(new CreateOperation("P-Asserted-Identity", "<sip:desk@example.com>")).process(invite);
			assertEquals(3, values(invite, "P-Asserted-Identity").size());
		}

		@Test
		void deleteWithAPatternRemovesOnlyTheMatches() throws Exception {
			DeleteOperation delete = new DeleteOperation("P-Asserted-Identity");
			delete.setPattern("^<tel:");
			rule(delete).process(invite);
			assertEquals(List.of("<sip:+15550100@example.com>"), values(invite, "P-Asserted-Identity"));
		}

		@Test
		void deleteWithoutAPatternRemovesThemAll() throws Exception {
			rule(new DeleteOperation("P-Asserted-Identity")).process(invite);
			assertTrue(values(invite, "P-Asserted-Identity").isEmpty());
		}
	}

	@Nested
	class Updates {

		@Test
		void everyMatchReplacesInPlace() throws Exception {
			DetachedRequest invite = invite();
			invite.setHeader("X-List", "a1,b2,c3");
			UpdateOperation update = new UpdateOperation("X-List", "(?<letter>[a-z])(?<digit>[0-9])",
					"${digit}${letter}");
			update.setEveryMatch(true);
			rule(update).process(invite);
			assertEquals("1a,2b,3c", invite.getHeader("X-List"));
		}

		@Test
		void anUnsetVariableSkipsTheOperationRatherThanSendingIt() throws Exception {
			DetachedRequest invite = invite();
			rule(new CreateOperation("X-Account", "${account}")).process(invite);
			assertNull(invite.getHeader("X-Account"));
		}

		@Test
		void aPlainNameNeverReachesTheEnvironment() throws Exception {
			DetachedRequest invite = invite();
			rule(new CreateOperation("X-Home", "${user.home}")).process(invite);
			assertNull(invite.getHeader("X-Home"));
			// Explicit, but not named for rules: still unreachable.
			rule(new CreateOperation("X-Home", "${sys:user.home}")).process(invite);
			assertNull(invite.getHeader("X-Home"));
		}

		@Test
		void aReadOnlyAttributeCannotBeWritten() throws Exception {
			DetachedRequest invite = invite();
			assertFalse(rule(new CreateOperation("peerIP", "10.0.0.1")).process(invite));
			assertNull(invite.getHeader("peerIP"), "no header named peerIP goes out");
		}
	}

	@Nested
	class Status {

		@Test
		void aFailureBecomesAnotherFailure() throws Exception {
			DetachedRequest invite = invite();
			DetachedResponse busy = new DetachedResponse(invite, 486);
			busy.setHeader("X-Cause", "agent busy");
			busy.setContent("detail", "text/plain");
			assertTrue(rule(new UpdateOperation("status", "^486$", "480")).process(busy));
			assertNull(busy.getHeader("status"), "no header named status goes out");

			SipServletResponse sent = CrudHook.restatus(busy);
			assertNotSame(busy, sent);
			assertEquals(480, sent.getStatus());
			assertEquals("agent busy", sent.getHeader("X-Cause"));
			assertEquals("detail", new String(sent.getRawContent()));
		}

		@Test
		void anAnsweredCallCannotBeTurnedIntoAFailure() throws Exception {
			DetachedResponse ok = new DetachedResponse(invite(), 200);
			assertFalse(rule(new CreateOperation("status", "486")).process(ok));
			assertSame(ok, CrudHook.restatus(ok));
		}

		@Test
		void aResponseNoRuleChangedIsSentAsItIs() throws Exception {
			DetachedResponse busy = new DetachedResponse(invite(), 486);
			assertSame(busy, CrudHook.restatus(busy));
		}
	}

	@Nested
	class Reject {

		@Test
		void anInitialInviteIsRefused() throws Exception {
			DetachedRequest invite = invite();
			assertTrue(rule(new RejectOperation(603, "Declined by policy")).process(invite));
			RejectOperation.Refusal refusal = CrudHook.refusal(invite);
			assertEquals(603, refusal.status);
			SipServletResponse answer = CrudHook.refuse(invite, refusal);
			assertEquals(603, answer.getStatus());
			assertEquals("Declined by policy", answer.getReasonPhrase());
		}

		@Test
		void aReinviteCannotBe() throws Exception {
			DetachedRequest reinvite = invite();
			reinvite.setInitial(false);
			assertFalse(rule(new RejectOperation(603, null)).process(reinvite));
			assertNull(CrudHook.refusal(reinvite));
		}
	}

	@Nested
	class AllOrNothing {

		@Test
		void aFailingOperationUndoesTheWholeRule() throws Exception {
			DetachedRequest invite = invite();
			invite.setHeader("X-Keep", "original");
			invite.setContent(SDP, "application/sdp");
			MessageHelper.putVariable(invite.getApplicationSession(), "seen", "before");
			Rule rule = rule(new UpdateOperation("X-Keep", "^.*$", "changed"),
					new CreateOperation("X-Added", "new"),
					new SdpDeleteOperation("$.media"),
					new ReadOperation("From", "sip:(?<seen>[^@]+)@"),
					new CreateOperation("status", "480")); // a request has no status: fails
			assertFalse(rule.process(invite));
			assertEquals("original", invite.getHeader("X-Keep"));
			assertNull(invite.getHeader("X-Added"));
			assertTrue(new String(invite.getRawContent()).contains("m=audio"));
			assertEquals("before", MessageHelper.getVariable(invite.getApplicationSession(), "seen"));
		}
	}

	@Nested
	class Filters {

		@Test
		void legTellsTheCallerFromTheCallee() throws Exception {
			DetachedRequest toCallee = invite();
			DetachedRequest toCaller = invite();
			toCaller.getSession().setAttribute("userAgent", "caller");
			assertEquals("callee", Rule.legOf(toCallee));
			assertEquals("caller", Rule.legOf(toCaller));

			Rule rule = rule(new CreateOperation("X-Leg", "callee"));
			rule.setLeg(Rule.Leg.callee);
			assertTrue(rule.matches(toCallee, Crud.SEND_EVENT));
			assertFalse(rule.matches(toCaller, Crud.SEND_EVENT));
		}

		@Test
		void sdpFollowsOfferAndAnswer() throws Exception {
			DetachedRequest earlyOffer = invite();
			earlyOffer.setContent(SDP, "application/sdp");
			assertEquals("offer", Rule.sdpRole(earlyOffer));
			DetachedResponse answer = new DetachedResponse(earlyOffer, 200);
			answer.setContent(SDP, "application/sdp");
			assertEquals("answer", Rule.sdpRole(answer));

			DetachedRequest lateOffer = invite();
			DetachedResponse offerIn200 = new DetachedResponse(lateOffer, 200);
			offerIn200.setContent(SDP, "application/sdp");
			assertEquals("offer", Rule.sdpRole(offerIn200));

			DetachedRequest ack = new DetachedRequest(new DetachedApplicationSession("app"), "ACK",
					"<sip:alice@example.com>;tag=a1", "<sip:bob@example.com>;tag=b1");
			ack.setContent(SDP, "application/sdp");
			assertEquals("answer", Rule.sdpRole(ack));

			assertNull(Rule.sdpRole(invite()), "no SDP, no role");
		}

		@Test
		void onlyARuleThatAsksRunsOnAReceivedMessage() throws Exception {
			Rule always = rule(new CreateOperation("X-Send", "yes"));
			Rule onReceive = rule(new CreateOperation("X-Receive", "yes"));
			onReceive.setEvent(Crud.RECEIVE_EVENT);
			DetachedRequest invite = invite();
			assertFalse(always.matches(invite, Crud.RECEIVE_EVENT));
			assertTrue(onReceive.matches(invite, Crud.RECEIVE_EVENT));
			assertTrue(always.matches(invite, Crud.SEND_EVENT));
			assertFalse(onReceive.matches(invite, Crud.SEND_EVENT));
		}

		@Test
		void theHookRunsReceiveRulesOnceAndSendRulesSeparately() throws Exception {
			Rule onReceive = rule(new CreateOperation("X-Seen", "received"));
			onReceive.setEvent(Crud.RECEIVE_EVENT);
			RuleSet ruleSet = new RuleSet();
			ruleSet.setId("only");
			ruleSet.add(onReceive);
			Configuration config = new Configuration();
			config.setCrud(new Crud());
			config.getCrud().getRuleSets().put("only", ruleSet);
			config.getCrud().setDefaultRuleSet("only");
			CrudHook.publish(config);
			try {
				DetachedRequest invite = invite();
				CrudHook.apply(invite);
				assertNull(invite.getHeader("X-Seen"));
				CrudHook.receive(invite);
				CrudHook.receive(invite);
				assertEquals(List.of("received"), values(invite, "X-Seen"));
			} finally {
				CrudHook.publish(new Configuration());
			}
		}
	}

	@Nested
	class XPathValues {

		@Test
		void aValueWithBothQuotesBecomesAConcat() {
			assertEquals("'plain'", Placeholders.xpathLiteral("plain"));
			assertEquals("\"it's\"", Placeholders.xpathLiteral("it's"));
			assertEquals("concat('say \"it', \"'\", 's\"')", Placeholders.xpathLiteral("say \"it's\""));
		}

		@Test
		void anXPathFindsTheNodeTheVariableNames() throws Exception {
			DetachedRequest invite = invite();
			invite.setContent("<a><b id=\"x'1\">one</b><b id=\"y\">two</b></a>", "application/xml");
			MessageHelper.putVariable(invite.getApplicationSession(), "id", "x'1");
			XPathReadOperation read = new XPathReadOperation();
			read.getExpressions().put("text", "//b[@id = ${id}]");
			rule(read).process(invite);
			assertEquals("one", MessageHelper.getVariable(invite.getApplicationSession(), "text"));
		}
	}
}
