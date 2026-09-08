package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// An utterance is a stored object read back by somebody else's code, so what
/// matters is what survives a plain mapper.
public class UtteranceTest {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	public void roundTripsWithTheRecognizersWordsIntact() throws Exception {
		Utterance before = new Utterance("caller", 8_430L, 10_130L, "I want to rent a car.");
		before.setSequence(7);
		before.setEngine("whisper");
		before.setModel("sherpa-onnx-whisper-small.en");
		before.setWords(json.readTree("{\"tokens\":[\" I\",\" want\"],\"timestamps\":[0.0,0.32]}"));

		String text = json.writeValueAsString(before);
		Utterance after = json.readValue(text, Utterance.class);

		assertEquals(7, after.getSequence());
		assertEquals("caller", after.getParty());
		assertEquals(8_430L, after.getStartMillis());
		assertEquals(10_130L, after.getEndMillis());
		assertEquals(1_700L, after.durationMillis());
		assertEquals("I want to rent a car.", after.getText());
		assertEquals("whisper", after.getEngine());
		assertEquals("sherpa-onnx-whisper-small.en", after.getModel());
		JsonNode words = after.getWords();
		assertEquals(" want", words.path("tokens").get(1).asText());
		assertEquals(0.32, words.path("timestamps").get(1).asDouble(), 1e-9);
	}

	@Test
	public void absentProvenanceIsAbsentNotEmpty() throws Exception {
		Utterance u = new Utterance("callee", 0L, 500L, "Hello?");
		u.setSequence(1);

		String text = json.writeValueAsString(u);

		assertFalse(text.contains("\"engine\""), "nothing claims an engine that was not named");
		assertFalse(text.contains("\"words\""));
		Utterance after = json.readValue(text, Utterance.class);
		assertNull(after.getEngine());
		assertNull(after.getWords());
	}

	/// A live transcript is a prefix and a count rather than a file, and both
	/// have to survive the trip.
	@Test
	public void aLiveTranscriptRefCarriesItsPrefixAndCount() throws Exception {
		TranscriptRef live = new TranscriptRef("live", "en-US", TranscriptRef.Attribution.PER_TRACK);
		live.setRedaction(TranscriptRef.Redaction.VERBATIM);
		live.setObject("transcript/live/");
		live.setUtterances(42);
		live.setComplete(true);
		live.getSource().add("mix");

		TranscriptRef after = json.readValue(json.writeValueAsString(live), TranscriptRef.class);

		assertEquals("transcript/live/", after.getObject());
		assertEquals(42, after.getUtterances());
		assertEquals(TranscriptRef.Attribution.PER_TRACK, after.getAttribution());
		assertTrue(after.isComplete());

		TranscriptRef whole = new TranscriptRef("t1", "en-US", TranscriptRef.Attribution.DIARIZED);
		whole.setObject("transcripts/t1.json");
		assertFalse(json.writeValueAsString(whole).contains("utterances"),
				"a single-object transcript claims no count");
	}

	@Test
	public void boundsThatCrossAreNotANegativeDuration() {
		Utterance u = new Utterance("caller", 900L, 800L, "uh");
		assertEquals(0L, u.durationMillis());
		assertTrue(u.getStartMillis() > u.getEndMillis(), "the stored values are kept as given");
	}
}
