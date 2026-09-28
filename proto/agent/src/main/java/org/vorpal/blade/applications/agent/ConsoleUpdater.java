package org.vorpal.blade.applications.agent;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Turns a bus event about a live call into an update on the agent's screen.
///
/// Each event carries the call's Vorpal-ID (its subject and `data.vorpalId`) and
/// its facts as fields beside it. Three kinds are read:
///
/// - **risk** (`call.risk.assessed` / `.flagged`): `riskBand`, `riskScore`, and
///   the per-signal `signal.<name>` values and `contribution.<name>` log-odds,
///   so the card can show not just how much but why;
/// - **utterance** (`call.utterance`): `text`, `party`, `startMs`, appended to
///   the card's transcript;
/// - **review** (`call.reviewed`): the labels a post-call review found.
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
		JsonNode data = event.fields();
		String vorpalId = data.path("vorpalId").asText(null);
		if (vorpalId == null && event.getSubject() != null) {
			// subject = <vorpalIdHex>.<tsHex>; the first half is the call.
			String subject = event.getSubject();
			int dot = subject.indexOf('.');
			vorpalId = (dot > 0) ? subject.substring(0, dot) : subject;
		}
		if (vorpalId == null || vorpalId.isEmpty()) {
			return null;
		}

		ObjectNode frame = MAPPER.createObjectNode();
		frame.put("t", "update");
		frame.put("vorpalId", vorpalId);

		if (BladeEventTypes.CALL_UTTERANCE.equals(event.getType())) {
			String text = text(data, "text");
			if (text == null || text.isEmpty()) {
				return null;
			}
			if (data.has("source")) {
				// A pass labelled a line already on the card, the phrase patterns
				// or the model: the page finds the line by its start time and adds
				// the labels; not a new line.
				ArrayNode labels = labels(data);
				if (labels.size() == 0) {
					return null;
				}
				ObjectNode labelled = frame.putObject("labelled");
				if (data.has("startMs")) {
					labelled.put("atMs", text(data, "startMs"));
				}
				labelled.put("text", text);
				labelled.put("source", text(data, "source"));
				labelled.set("labels", labels);
				return new Update(vorpalId, frame.toString());
			}
			ObjectNode utterance = frame.putObject("utterance");
			// The listener names the parties caller and callee; on this screen
			// the callee is the agent reading it.
			String party = data.path("party").asText("caller");
			utterance.put("party", "callee".equals(party) ? "agent" : party);
			utterance.put("text", text);
			if (data.has("startMs")) {
				utterance.put("atMs", text(data, "startMs"));
			}
			// What the probe heard in it: the content labels, if any, as words the
			// page maps to a reason ("asked for a gift card").
			ArrayNode labels = labels(data);
			if (labels.size() > 0) {
				utterance.set("labels", labels);
			}
			return new Update(vorpalId, frame.toString());
		}

		if (BladeEventTypes.CALL_REVIEWED.equals(event.getType())) {
			// The post-call review, which lands on the card still on the agent's screen.
			ArrayNode labels = labels(data);
			if (labels.size() == 0) {
				return null;
			}
			ObjectNode review = frame.putObject("review");
			review.set("labels", labels);
			if (data.has("text")) {
				review.put("text", text(data, "text"));
			}
			return new Update(vorpalId, frame.toString());
		}

		String band = text(data, "riskBand");
		String score = text(data, "riskScore");
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
		java.util.Iterator<String> names = data.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			if (name.startsWith("signal.") || name.startsWith("contribution.")) {
				if (signals == null) {
					signals = frame.putObject("signals");
				}
				int dot = name.indexOf('.');
				String signal = name.substring(dot + 1).toLowerCase(Locale.ROOT);
				ObjectNode one = signals.has(signal) ? (ObjectNode) signals.get(signal) : signals.putObject(signal);
				one.put(name.startsWith("signal.") ? "value" : "contribution", text(data, name));
			}
		}
		if (data.has("triggerSignal")) {
			frame.put("riskTrigger", text(data, "triggerSignal").toLowerCase(Locale.ROOT));
		}
		// The campaign check's facts, when the behaviour signal fired on them.
		if (data.has("campaignMatches")) {
			frame.put("campaignMatches", text(data, "campaignMatches"));
			if (data.has("campaignText")) {
				frame.put("campaignText", text(data, "campaignText"));
			}
		}
		return new Update(vorpalId, frame.toString());
	}

	/// A field as the text the page has always been sent. The page parses the
	/// numbers itself, so a typed field and a legacy string reach it the same.
	private static String text(JsonNode data, String name) {
		JsonNode value = data.get(name);
		return (value == null || value.isNull()) ? null : value.asText();
	}

	/// The content labels: an array on the flat wire, a comma-separated string
	/// from a producer on an older framework.
	private static ArrayNode labels(JsonNode data) {
		ArrayNode labels = MAPPER.createArrayNode();
		JsonNode raw = data.get("labels");
		if (raw == null || raw.isNull()) {
			return labels;
		}
		if (raw.isArray()) {
			for (JsonNode label : raw) {
				if (!label.asText().trim().isEmpty()) {
					labels.add(label.asText().trim());
				}
			}
			return labels;
		}
		for (String label : raw.asText().split(",")) {
			if (!label.trim().isEmpty()) {
				labels.add(label.trim());
			}
		}
		return labels;
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
