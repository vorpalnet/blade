package org.vorpal.blade.applications.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

import com.fasterxml.jackson.databind.ObjectMapper;

/// What a reviewer is handed, built from a manifest and utterances shaped
/// like the ones the rig stores.
class TranscriptViewTest {

	@Test
	@DisplayName("a recording id names its conversation with the timestamp in lower case")
	void recordingIdToConversation() {
		assertEquals("1F17594D.1a07e772b4e", TranscriptView.conversationOf("1F17594D.1A07E772B4E"));
		assertEquals("1F17594D.1a07e772b4e", TranscriptView.conversationOf("1f17594d.1a07e772b4e"));
		assertEquals("1F17594D", TranscriptView.conversationOf("1f17594d"));
		assertNull(TranscriptView.conversationOf(null));
	}

	@Test
	@DisplayName("lines come out in time order with words on the conversation clock and corrections beside what was heard")
	@SuppressWarnings("unchecked")
	void theViewCarriesTheRecord() throws Exception {
		ConversationManifest manifest = new ConversationManifest("1F17594D.1a07e772b4e", "1F17594D.1a07e772aff",
				Instant.parse("2026-09-08T00:42:14Z"));
		manifest.setDurationMillis(65_641L);
		manifest.setComplete(true);
		TranscriptRef live = new TranscriptRef("live", "en-US", TranscriptRef.Attribution.PER_TRACK);
		live.setRedaction(TranscriptRef.Redaction.VERBATIM);
		live.setEngine("nemo-transducer");
		live.setModel("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8");
		live.setObject("transcript/live/");
		live.setUtterances(2);
		live.setComplete(true);
		manifest.addTranscript(live);

		Utterance second = new Utterance("caller", 1622L, 3022L, "I need to rent a car.");
		second.setSequence(2);
		second.setWords(new ObjectMapper().readTree("{\"tokens\":[\" I\",\" need\"],\"timestamps\":[0.24,0.48],\"durations\":[0.24,0.24]}"));
		Utterance first = new Utterance("callee", 1106L, 2966L, "Hello, this is Yolanda Okonkwo.");
		first.setSequence(1);
		first.setHeard("Hello, this is Yolanda of Conquer.");
		first.setCorrections(List.of(new Utterance.Correction("Yolanda of Conquer.", "Yolanda Okonkwo")));

		Map<String, Object> body = TranscriptView.of(manifest, Map.of("live", List.of(second, first)));

		assertEquals("1F17594D.1a07e772b4e", body.get("conversation"));
		List<Map<String, Object>> transcripts = (List<Map<String, Object>>) body.get("transcripts");
		assertEquals(1, transcripts.size());
		assertEquals("PER_TRACK", transcripts.get(0).get("attribution"));
		assertEquals(2, transcripts.get(0).get("utterancesExpected"));
		List<Map<String, Object>> lines = (List<Map<String, Object>>) transcripts.get(0).get("utterances");
		assertEquals(2, lines.size());
		assertEquals(1, lines.get(0).get("sequence"), "time order, not arrival order");
		assertEquals("Hello, this is Yolanda of Conquer.", lines.get(0).get("heard"));
		List<Map<String, String>> corrections = (List<Map<String, String>>) lines.get(0).get("corrections");
		assertEquals("Yolanda Okonkwo", corrections.get(0).get("to"));
		assertFalse(lines.get(1).containsKey("heard"), "an uncorrected line claims no correction");
		List<Map<String, Object>> words = (List<Map<String, Object>>) lines.get(1).get("words");
		assertEquals("need", words.get(1).get("text"));
		assertEquals(1622L + 480L, words.get(1).get("startMillis"));
	}
}
