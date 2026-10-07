package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// [ConsoleUpdater#toUpdate] is a pure function of the bus event, so the
/// whole risk-event → console-frame contract is tested here without a broker or
/// a socket: the Vorpal-ID is the key, the band is the console's lower-case
/// word, and a flagged event says so.
class ConsoleUpdaterTest {

	private static final ObjectMapper M = new ObjectMapper();

	/// A risk event in the legacy wire shape, the verdict as name/value
	/// attributes, which [CloudEvent#fields] still reads during the transition.
	private static CloudEvent riskEvent(String type, String vorpalId, String band, String score) {
		ObjectNode data = M.createObjectNode();
		data.put("eventName", "callRiskAssessed");
		if (vorpalId != null) {
			data.put("vorpalId", vorpalId);
		}
		ArrayNode attributes = data.putArray("attributes");
		if (band != null) {
			attributes.addObject().put("name", "riskBand").put("value", band);
		}
		if (score != null) {
			attributes.addObject().put("name", "riskScore").put("value", score);
		}
		attributes.addObject().put("name", "signal.acoustic").put("value", "0.810");
		String subject = (vorpalId == null) ? null : vorpalId + ".18F3A2B4C10";
		return CloudEvent.create(type, "/gryphon/agent-risk", subject, data);
	}

	@Test
	void assessedBecomesAnUpdateFrameKeyedByVorpalId() throws Exception {
		ConsoleUpdater.Update u = ConsoleUpdater
				.toUpdate(riskEvent(BladeEventTypes.CALL_RISK_ASSESSED, "0BADF00D", "WATCH", "0.550"));

		assertNotNull(u);
		assertEquals("0BADF00D", u.vorpalId);
		JsonNode frame = M.readTree(u.json);
		assertEquals("update", frame.path("t").asText());
		assertEquals("0BADF00D", frame.path("vorpalId").asText());
		assertEquals("watch", frame.path("riskBand").asText(), "the console's lower-case word, not the enum");
		assertEquals("0.550", frame.path("riskScore").asText());
		assertFalse(frame.has("riskFlagged"));
		assertEquals("0.810", frame.path("signals").path("acoustic").path("value").asText(),
				"the per-signal why rides along, keyed by the signal's lower-case name");
	}

	@Test
	void anUtteranceBecomesATranscriptLine() throws Exception {
		ObjectNode data = M.createObjectNode();
		data.put("eventName", "callerSaid");
		data.put("vorpalId", "0BADF00D");
		ArrayNode attributes = data.putArray("attributes");
		attributes.addObject().put("name", "text").put("value", "I need to rent a car.");
		attributes.addObject().put("name", "party").put("value", "caller");
		attributes.addObject().put("name", "startMs").put("value", "2140");
		CloudEvent event = CloudEvent.create(BladeEventTypes.CALL_UTTERANCE, "/gryphon/agent-risk",
				"0BADF00D.18F3A2B4C10", data);

		ConsoleUpdater.Update u = ConsoleUpdater.toUpdate(event);

		assertNotNull(u);
		JsonNode frame = M.readTree(u.json);
		assertEquals("update", frame.path("t").asText());
		assertEquals("I need to rent a car.", frame.path("utterance").path("text").asText());
		assertEquals("caller", frame.path("utterance").path("party").asText());
		assertEquals("2140", frame.path("utterance").path("atMs").asText());
		assertFalse(frame.has("riskBand"), "an utterance never touches the risk line");
	}

	@Test
	void flaggedMarksTheFrame() throws Exception {
		ConsoleUpdater.Update u = ConsoleUpdater
				.toUpdate(riskEvent(BladeEventTypes.CALL_RISK_FLAGGED, "0BADF00D", "SUSPECT", "0.880"));

		JsonNode frame = M.readTree(u.json);
		assertEquals("suspect", frame.path("riskBand").asText());
		assertTrue(frame.path("riskFlagged").asBoolean());
	}

	@Test
	void vorpalIdFallsBackToTheSubject() throws Exception {
		// No data.vorpalId; the subject's first half is the call.
		ObjectNode data = M.createObjectNode();
		data.putArray("attributes").addObject().put("name", "riskBand").put("value", "CLEAR");
		CloudEvent event = CloudEvent.create(BladeEventTypes.CALL_RISK_ASSESSED, "/x", "0BADF00D.18F3A2B4C10", data);

		ConsoleUpdater.Update u = ConsoleUpdater.toUpdate(event);

		assertNotNull(u);
		assertEquals("0BADF00D", u.vorpalId);
	}

	@Test
	void anEventWithNoCallOrNoRiskIsIgnored() {
		assertNull(ConsoleUpdater.toUpdate(null));
		// Names a call but carries neither band nor score: nothing to show.
		assertNull(ConsoleUpdater.toUpdate(riskEvent(BladeEventTypes.CALL_RISK_ASSESSED, "0BADF00D", null, null)));
		// Carries a verdict but names no call: nowhere to put it.
		assertNull(ConsoleUpdater.toUpdate(riskEvent(BladeEventTypes.CALL_RISK_ASSESSED, null, "WATCH", "0.5")));
	}

	/// The flat wire: typed fields beside the correlator, labels as an array.
	/// The page is sent the same text it always was.
	@Test
	void aFlatUtteranceWithLabelsBecomesTheSameLine() throws Exception {
		ObjectNode data = M.createObjectNode();
		data.put("vorpalId", "0BADF00D");
		data.put("text", "Buy a gift card.");
		data.put("party", "callee");
		data.put("startMs", 2140L);
		data.putArray("labels").add("gift-card").add("urgency");
		CloudEvent event = CloudEvent.create(BladeEventTypes.CALL_UTTERANCE, "/blade/listener",
				"0BADF00D.18F3A2B4C10", data);

		JsonNode frame = M.readTree(ConsoleUpdater.toUpdate(event).json);

		assertEquals("agent", frame.path("utterance").path("party").asText());
		assertEquals("2140", frame.path("utterance").path("atMs").asText());
		assertEquals(2, frame.path("utterance").path("labels").size());
		assertEquals("urgency", frame.path("utterance").path("labels").get(1).asText());
	}

	@Test
	void aFlatRiskScoreReachesThePageAsText() throws Exception {
		ObjectNode data = M.createObjectNode();
		data.put("vorpalId", "0BADF00D");
		data.put("riskScore", 0.55);
		data.put("riskBand", "SUSPECT");
		data.put("signal.acoustic", 0.81);
		JsonNode frame = M.readTree(ConsoleUpdater
				.toUpdate(CloudEvent.create(BladeEventTypes.CALL_RISK_FLAGGED, "/gryphon", null, data)).json);

		assertEquals("0.55", frame.path("riskScore").asText());
		assertEquals("suspect", frame.path("riskBand").asText());
		assertTrue(frame.path("riskFlagged").asBoolean());
		assertEquals("0.81", frame.path("signals").path("acoustic").path("value").asText());
	}

	@Test
	void aReviewLandsOnTheCard() throws Exception {
		ObjectNode data = M.createObjectNode();
		data.put("vorpalId", "0BADF00D");
		data.putArray("labels").add("scam-script");
		data.put("text", "Read me the code.");
		JsonNode frame = M.readTree(ConsoleUpdater
				.toUpdate(CloudEvent.create(BladeEventTypes.CALL_REVIEWED, "/gryphon", null, data)).json);

		assertEquals("scam-script", frame.path("review").path("labels").get(0).asText());
		assertEquals("Read me the code.", frame.path("review").path("text").asText());
	}

	/// A call-started or call-completed event as an application that answers its
	/// own calls publishes it: the producer's name in `appName`, the caller's
	/// headers beside it.
	private static CloudEvent boundary(String type, String app) {
		ObjectNode data = M.createObjectNode();
		data.put("vorpalId", "1A2B3C4D");
		data.put("startedAt", "2026-10-07T05:00:00Z");
		data.put("appName", app);
		data.put("caller", "\"Pat Caller\" <sip:+19165550123@carrier.example.com>;tag=1");
		data.put("destination", "<sip:+14155550002@rig.example.com>");
		return CloudEvent.create(type, "/blade/" + app, "1A2B3C4D.199bd1f1a00", data);
	}

	@Test
	void onlyWatchedApplicationsMakeCards() {
		java.util.List<String> watched = java.util.Arrays.asList("room");
		assertTrue(ConsoleUpdater.watched(boundary(BladeEventTypes.CALL_STARTED, "room"), watched));
		assertFalse(ConsoleUpdater.watched(boundary(BladeEventTypes.CALL_STARTED, "agent"), watched),
				"this app's own calls already have a card from the INVITE");
		assertFalse(ConsoleUpdater.watched(boundary(BladeEventTypes.CALL_STARTED, "room"), null),
				"nothing watched by default");
		assertTrue(ConsoleUpdater.isCallBoundary(boundary(BladeEventTypes.CALL_COMPLETED, "room")));
		assertFalse(ConsoleUpdater.isCallBoundary(riskEvent(BladeEventTypes.CALL_RISK_ASSESSED, "0BADF00D", "WATCH", "0.5")));
	}

	@Test
	void callStartedBuildsTheCardFromTheCallersHeaders() {
		CallPop pop = ConsoleUpdater.popOf(boundary(BladeEventTypes.CALL_STARTED, "room"));

		assertNotNull(pop);
		assertEquals("1A2B3C4D", pop.vorpalId, "the key the risk and transcript updates find the card by");
		assertEquals("9165550123", pop.ani);
		assertEquals("Pat Caller", pop.displayName);
		assertEquals("4155550002", pop.dialed);
	}

	@Test
	void callCompletedEndsTheCard() throws Exception {
		ConsoleUpdater.Update u = ConsoleUpdater.toUpdate(boundary(BladeEventTypes.CALL_COMPLETED, "room"));

		assertNotNull(u);
		assertEquals("ended", M.readTree(u.json).path("state").asText());
	}
}
