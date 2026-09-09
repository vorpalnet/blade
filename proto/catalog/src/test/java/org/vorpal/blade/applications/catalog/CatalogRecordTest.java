package org.vorpal.blade.applications.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.MediaGap;
import org.vorpal.blade.framework.v3.media.manifest.RecordingTrack;
import org.vorpal.blade.framework.v3.media.manifest.Redactor;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// What the catalog derives from a record, and what it refuses to keep.
class CatalogRecordTest {

	private static ConversationManifest manifest(TranscriptRef.Redaction redaction) {
		ConversationManifest m = new ConversationManifest("0C360643.1a08456988d", "0C360643.1a084569831",
				Instant.parse("2026-09-09T04:04:22Z"));
		m.setDurationMillis(13_512L);
		m.setComplete(true);
		m.setFinalizedBy("engine0");
		m.getAttributes().put("call", "0C360643.1a084569831");
		m.getAttributes().put("from", "sipp");
		m.getAttributes().put("to", "record9");
		m.getAttributes().put("department", "cardiology");
		RecordingTrack mix = new RecordingTrack("mix", RecordingTrack.Role.MIX);
		mix.addGap(new MediaGap(3000, 5000, MediaGap.Reason.HOLD));
		mix.addGap(new MediaGap(9000, 9034, MediaGap.Reason.MOVED));
		m.addTrack(mix);
		TranscriptRef live = new TranscriptRef("live", "en-US", TranscriptRef.Attribution.PER_TRACK);
		live.setRedaction(redaction);
		live.setObject("transcript/live/");
		m.addTranscript(live);
		return m;
	}

	private static Map<String, List<Utterance>> utterances() {
		Utterance a = new Utterance("caller", 1602, 3000, "Hello, this is Alice.");
		a.setSequence(1);
		Utterance b = new Utterance("caller", 7722, 9000, "My card is 4111 1111 1111 1111 and my id is KR742916.");
		b.setSequence(2);
		Redactor.defaults().apply(a);
		Redactor.defaults().apply(b);
		Map<String, List<Utterance>> out = new LinkedHashMap<>();
		out.put("live", Arrays.asList(a, b));
		return out;
	}

	@Test
	@DisplayName("the facts a supervisor filters on come from the manifest")
	void derivesTheFacts() {
		CatalogRecord r = CatalogRecord.of(manifest(TranscriptRef.Redaction.REDACTED), utterances(), "k");
		assertEquals("sipp", r.from);
		assertEquals("record9", r.to);
		assertEquals("cardiology", r.attributes.get("department"));
		assertEquals(1, r.holds);
		assertEquals(1, r.moves);
		assertEquals(2, r.utterances);
		assertEquals("card=1,number=1", r.kindsColumn());
		assertEquals("engine0", r.node);
	}

	@Test
	@DisplayName("what is searchable is the redacted rendition; the verbatim text is never stored")
	void storesRedactedTextOnly() {
		CatalogRecord r = CatalogRecord.of(manifest(TranscriptRef.Redaction.REDACTED), utterances(), "k");
		assertEquals("Hello, this is Alice.", r.lines.get(0).text);
		assertEquals("My card is [card] and my id is [number].", r.lines.get(1).text);
		assertEquals("card,number", r.lines.get(1).kinds);
		for (CatalogRecord.Line line : r.lines) {
			assertFalse(line.text.contains("4111"), "a card number in the catalog would be a second copy of it");
			assertFalse(line.text.contains("KR742916"));
		}
	}

	@Test
	@DisplayName("a transcript the recorder never redacted is stored as it is")
	void keepsAVerbatimTranscriptVerbatim() {
		Map<String, List<Utterance>> plain = new LinkedHashMap<>();
		Utterance u = new Utterance("caller", 0, 1000, "I need to rent a car.");
		u.setSequence(1);
		plain.put("live", Collections.singletonList(u));
		CatalogRecord r = CatalogRecord.of(manifest(TranscriptRef.Redaction.VERBATIM), plain, null);
		assertEquals("I need to rent a car.", r.lines.get(0).text);
		assertTrue(r.protectedValues.isEmpty());
	}

	@Test
	@DisplayName("protected values are kept as keyed hashes of their normalised form")
	void hashesProtectedValues() {
		CatalogRecord r = CatalogRecord.of(manifest(TranscriptRef.Redaction.REDACTED), utterances(), "secret");
		assertEquals(2, r.protectedValues.size());
		String card = CatalogRecord.hmac("secret", CatalogRecord.normalise("4111-1111-1111-1111"));
		assertEquals(card, r.protectedValues.get(0).digest, "separators do not change the value");
		assertNotEquals(card, CatalogRecord.hmac("other-key", CatalogRecord.normalise("4111111111111111")),
				"a different deployment's key gives a different hash");
		assertEquals("KR742916", CatalogRecord.normalise("kr-742 916"));
		assertTrue(CatalogRecord.of(manifest(TranscriptRef.Redaction.REDACTED), utterances(), "").protectedValues.isEmpty(),
				"no key, no hashes");
	}
}
