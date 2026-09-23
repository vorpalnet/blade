package org.vorpal.blade.applications.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Turns a bus event about a live call into an update on the agent's screen.
///
/// Each event carries the call's Vorpal-ID (its subject and `data.vorpalId`) and
/// its facts as name/value attributes. Two kinds are read:
///
/// - **risk** (`call.risk.assessed` / `.flagged`): `riskBand`, `riskScore`, and
///   the per-signal `signal.<name>` values and `contribution.<name>` log-odds,
///   so the card can show not just how much but why;
/// - **utterance** (`call.utterance`): `text`, `party`, `startMs`, appended to
///   the card's transcript.
///
/// Either becomes the console's one general update frame, a partial `CallPop`
/// keyed by Vorpal-ID, pushed to the console holding that call
/// ([AgentConsoleRegistry#sendToCall]). The page merges it and redraws in place.
///
/// The frame is deliberately not risk-specific: `{"t":"update","vorpalId":…,
/// <fields>}`. A later producer (a topic, a CRM match, a re-screen) sends the
/// same shape with different fields and the page treats it the same way.
///
/// Best-effort throughout. An event it cannot read is skipped, never rethrown:
/// this is a non-durable subscription feeding a screen, and a redelivery would
/// only repeat the failure. A lost update is worth less than a stalled consumer.
public class ConsoleUpdater implements EventSubscriber.Handler {

	private static final Logger LOG = Logger.getLogger(ConsoleUpdater.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public void handle(List<CloudEvent> batch) {
		for (CloudEvent event : batch) {
			try {
				Update update = toUpdate(event);
				if (update != null) {
					String where = AgentConsoleRegistry.sendToCall(update.vorpalId, update.json);
					AgentConsoleRegistry.log("agent: update vorpalId=" + update.vorpalId + " " + update.json + " -> " + where);
				}
			} catch (Exception e) {
				LOG.log(Level.FINE, "agent: risk event not applied: " + e.getMessage());
			}
		}
	}

	/// The update frame for one event, or null if the event names no call or
	/// carries nothing the console shows. A pure function of the event, so it is
	/// tested without a bus or a socket.
	static Update toUpdate(CloudEvent event) {
		if (event == null) {
			return null;
		}
		JsonNode data = event.getData();
		String vorpalId = (data == null) ? null : data.path("vorpalId").asText(null);
		if (vorpalId == null && event.getSubject() != null) {
			// subject = <vorpalIdHex>.<tsHex>; the first half is the call.
			String subject = event.getSubject();
			int dot = subject.indexOf('.');
			vorpalId = (dot > 0) ? subject.substring(0, dot) : subject;
		}
		if (vorpalId == null || vorpalId.isEmpty()) {
			return null;
		}

		Map<String, String> attributes = new LinkedHashMap<>();
		JsonNode list = (data == null) ? null : data.path("attributes");
		if (list != null && list.isArray()) {
			for (JsonNode attribute : list) {
				String name = attribute.path("name").asText(null);
				String value = attribute.path("value").asText(null);
				if (name != null && value != null) {
					attributes.put(name, value);
				}
			}
		}

		ObjectNode frame = MAPPER.createObjectNode();
		frame.put("t", "update");
		frame.put("vorpalId", vorpalId);

		if (BladeEventTypes.CALL_UTTERANCE.equals(event.getType())) {
			String text = attributes.get("text");
			if (text == null || text.isEmpty()) {
				return null;
			}
			if ("model".equals(attributes.get("source"))) {
				// A later pass labelled a line already on the card: the page finds
				// the line by its start time and adds the labels; not a new line.
				String labels = attributes.get("labels");
				if (labels == null || labels.isEmpty()) {
					return null;
				}
				ObjectNode labelled = frame.putObject("labelled");
				if (attributes.containsKey("startMs")) {
					labelled.put("atMs", attributes.get("startMs"));
				}
				labelled.put("text", text);
				com.fasterxml.jackson.databind.node.ArrayNode modelLabels = labelled.putArray("labels");
				for (String label : labels.split(",")) {
					if (!label.trim().isEmpty()) {
						modelLabels.add(label.trim());
					}
				}
				return new Update(vorpalId, frame.toString());
			}
			ObjectNode utterance = frame.putObject("utterance");
			utterance.put("party", attributes.getOrDefault("party", "caller"));
			utterance.put("text", text);
			if (attributes.containsKey("startMs")) {
				utterance.put("atMs", attributes.get("startMs"));
			}
			// What the probe heard in it: the content labels, if any, as words the
			// page maps to a reason ("asked for a gift card").
			String labels = attributes.get("labels");
			if (labels != null && !labels.isEmpty()) {
				com.fasterxml.jackson.databind.node.ArrayNode labelList = utterance.putArray("labels");
				for (String label : labels.split(",")) {
					if (!label.trim().isEmpty()) {
						labelList.add(label.trim());
					}
				}
			}
			return new Update(vorpalId, frame.toString());
		}

		if (BladeEventTypes.CALL_EVENT.equals(event.getType())) {
			// An application-named event: the only one the console shows is the
			// post-call review, which lands on the card still on the agent's screen.
			if (data == null || !"callReviewed".equals(data.path("eventName").asText(""))) {
				return null;
			}
			String labels = attributes.get("labels");
			if (labels == null || labels.isEmpty()) {
				return null;
			}
			ObjectNode review = frame.putObject("review");
			com.fasterxml.jackson.databind.node.ArrayNode reviewLabels = review.putArray("labels");
			for (String label : labels.split(",")) {
				if (!label.trim().isEmpty()) {
					reviewLabels.add(label.trim());
				}
			}
			if (attributes.containsKey("text")) {
				review.put("text", attributes.get("text"));
			}
			return new Update(vorpalId, frame.toString());
		}

		String band = attributes.get("riskBand");
		String score = attributes.get("riskScore");
		if (band == null && score == null) {
			return null;
		}
		if (band != null) {
			// The engine names the band as an enum (WATCH); the console's vocabulary
			// is the lower-case word the pop already uses.
			frame.put("riskBand", band.toLowerCase(Locale.ROOT));
		}
		if (score != null) {
			frame.put("riskScore", score);
		}
		if (BladeEventTypes.CALL_RISK_FLAGGED.equals(event.getType())) {
			frame.put("riskFlagged", true);
		}
		// The why: each fused signal's raw value and what it contributed, keyed by
		// the signal's lower-case name. Absent signals are simply not listed.
		ObjectNode signals = null;
		for (Map.Entry<String, String> e : attributes.entrySet()) {
			String name = e.getKey();
			if (name.startsWith("signal.") || name.startsWith("contribution.")) {
				if (signals == null) {
					signals = frame.putObject("signals");
				}
				int dot = name.indexOf('.');
				String signal = name.substring(dot + 1).toLowerCase(Locale.ROOT);
				ObjectNode one = signals.has(signal) ? (ObjectNode) signals.get(signal) : signals.putObject(signal);
				one.put(name.startsWith("signal.") ? "value" : "contribution", e.getValue());
			}
		}
		if (attributes.containsKey("triggerSignal")) {
			frame.put("riskTrigger", attributes.get("triggerSignal").toLowerCase(Locale.ROOT));
		}
		// The campaign check's facts, when the behaviour signal fired on them.
		if (attributes.containsKey("campaignMatches")) {
			frame.put("campaignMatches", attributes.get("campaignMatches"));
			if (attributes.containsKey("campaignText")) {
				frame.put("campaignText", attributes.get("campaignText"));
			}
		}
		return new Update(vorpalId, frame.toString());
	}

	/// One update: which call, and the frame to push.
	static final class Update {
		final String vorpalId;
		final String json;

		Update(String vorpalId, String json) {
			this.vorpalId = vorpalId;
			this.json = json;
		}
	}
}
