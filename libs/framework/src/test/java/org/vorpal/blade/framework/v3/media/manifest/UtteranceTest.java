package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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

	/// The tokens a transducer returned for "I need to rent a car." on the
	/// rig, 2026-09-08: a word's first token carries a leading space, "rent"
	/// arrives in two pieces, and the full stop attaches to "car".
	@Test
	public void wordTimesComeFromTheTokensOnTheConversationClock() throws Exception {
		Utterance u = new Utterance("caller", 1622L, 3022L, "I need to rent a car.");
		u.setWords(json.readTree("{\"tokens\":[\" I\",\" need\",\" to\",\" re\",\"nt\",\" a\",\" car\",\".\"],"
				+ "\"timestamps\":[0.24,0.48,0.72,0.80,0.96,1.12,1.28,1.52],"
				+ "\"durations\":[0.24,0.24,0.08,0.16,0.16,0.16,0.24,0.32]}"));

		List<WordTime> words = u.wordTimes();

		assertEquals(6, words.size());
		assertEquals("I", words.get(0).text());
		assertEquals(1622L + 240L, words.get(0).startMillis());
		assertEquals(1622L + 480L, words.get(0).endMillis());
		assertEquals("rent", words.get(3).text());
		assertEquals(1622L + 800L, words.get(3).startMillis());
		assertEquals(1622L + 1120L, words.get(3).endMillis(), "ends where its second piece ends");
		assertEquals("car.", words.get(5).text());
		assertEquals(1622L + 1280L, words.get(5).startMillis());
		assertEquals(1622L + 1840L, words.get(5).endMillis(), "the full stop's duration counts");
	}

	@Test
	public void noTimingMeansNoWords() throws Exception {
		Utterance u = new Utterance("caller", 0L, 500L, "hello");
		assertTrue(u.wordTimes().isEmpty());
		u.setWords(json.readTree("{\"tokens\":[\" hello\"],\"timestamps\":[]}"));
		assertTrue(u.wordTimes().isEmpty(), "whisper as exported: tokens without timestamps");
	}
}
