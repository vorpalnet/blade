package org.vorpal.blade.applications.recordings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;
import org.vorpal.blade.framework.v3.media.manifest.WordTime;

/// A conversation's transcript as the review API hands it over.
///
/// Built from the committed manifest and the stored utterances, and nothing
/// else: what a reviewer sees is what the record holds. Each line carries its
/// bounds on the conversation clock, its words placed on the same clock, the
/// engine and model that produced it, and where the text was corrected toward
/// an expected name, both the correction and what was heard.
///
/// ## The recording id and the conversation id differ in case
///
/// A recording is filed under its identifier with both halves upper-cased,
/// which is how the archive lists it and how a caller names it here. A
/// conversation is named by the same correlator with the timestamp in the
/// lower-case hexadecimal the event mapper writes. [#conversationOf] bridges
/// the two, so a caller who found a recording in a listing can ask for its
/// transcript by the same id.
final class TranscriptView {

	private TranscriptView() {
	}

	/// The conversation id a recording id refers to.
	static String conversationOf(String recordingId) {
		if (recordingId == null) {
			return null;
		}
		int dot = recordingId.indexOf('.');
		if (dot < 0) {
			return recordingId.toUpperCase(Locale.ROOT);
		}
		return recordingId.substring(0, dot).toUpperCase(Locale.ROOT) + "."
				+ recordingId.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/// The response body: the manifest's transcript entries, each with its
	/// utterances in time order.
	static Map<String, Object> of(ConversationManifest manifest, Map<String, List<Utterance>> utterancesByTranscript) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("conversation", manifest.getConversation());
		body.put("call", manifest.getCall());
		body.put("epochUtc", manifest.getEpochUtc());
		body.put("durationMillis", manifest.getDurationMillis());
		body.put("complete", manifest.isComplete());

		List<Map<String, Object>> transcripts = new ArrayList<>();
		for (TranscriptRef ref : manifest.getTranscripts()) {
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("id", ref.getId());
			t.put("language", ref.getLanguage());
			t.put("attribution", ref.getAttribution() == null ? null : ref.getAttribution().name());
			t.put("redaction", ref.getRedaction() == null ? null : ref.getRedaction().name());
			t.put("engine", ref.getEngine());
			t.put("model", ref.getModel());
			t.put("complete", ref.isComplete());
			t.put("utterancesExpected", ref.getUtterances());

			List<Utterance> utterances = utterancesByTranscript.get(ref.getId());
			List<Map<String, Object>> lines = new ArrayList<>();
			if (utterances != null) {
				List<Utterance> ordered = new ArrayList<>(utterances);
				ordered.sort((a, b) -> Long.compare(a.getStartMillis(), b.getStartMillis()));
				for (Utterance u : ordered) {
					lines.add(line(u));
				}
			}
			t.put("utterances", lines);
			transcripts.add(t);
		}
		body.put("transcripts", transcripts);
		return body;
	}

	private static Map<String, Object> line(Utterance u) {
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("sequence", u.getSequence());
		line.put("party", u.getParty());
		line.put("startMillis", u.getStartMillis());
		line.put("endMillis", u.getEndMillis());
		line.put("text", u.getText());
		if (u.getHeard() != null) {
			line.put("heard", u.getHeard());
			List<Map<String, String>> corrections = new ArrayList<>();
			if (u.getCorrections() != null) {
				for (Utterance.Correction c : u.getCorrections()) {
					Map<String, String> m = new LinkedHashMap<>();
					m.put("from", c.getFrom());
					m.put("to", c.getTo());
					corrections.add(m);
				}
			}
			line.put("corrections", corrections);
		}
		line.put("engine", u.getEngine());
		line.put("model", u.getModel());
		List<Map<String, Object>> words = new ArrayList<>();
		for (WordTime w : u.wordTimes()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("text", w.text());
			m.put("startMillis", w.startMillis());
			m.put("endMillis", w.endMillis());
			words.add(m);
		}
		line.put("words", words);
		return line;
	}
}
