package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The manifest as a stored contract.
///
/// These tests are about what survives being written down and read back by
/// someone else's code, which is the only thing a stored format has to do.
public class ConversationManifestTest {

	private static final Instant EPOCH = Instant.parse("2026-09-07T04:11:01.474Z");

	/// A deliberately plain mapper. The manifest has to round-trip through one
	/// that knows nothing about our conventions, which is why every timestamp is
	/// a string rather than a type needing a registered module.
	private final ObjectMapper json = new ObjectMapper();

	private ConversationManifest conference() {
		ConversationManifest manifest = new ConversationManifest("conv-1", "call-1", EPOCH);
		manifest.setDurationMillis(20_000L);
		manifest.setSyncSource("rtcp-sr");
		manifest.setSyncAccuracyMillis(20);
		manifest.setComplete(true);
		manifest.getAttributes().put("department", "cardiology");

		RecordingTrack mix = new RecordingTrack("mix", RecordingTrack.Role.MIX);
		mix.setOffsetMillis(0L);
		mix.setDurationMillis(20_000L);
		mix.setRate(48000);
		mix.setChannels(2);
		mix.setLevelDbfs(-28.0);
		mix.setClock(new TrackClock(48000).anchor(0L, EPOCH, "rtcp-sr"));
		mix.addSegment(new MediaSegment(0, 0L, 20_000L, "seg-0000.m4a"));
		manifest.addTrack(mix);

		RecordingTrack bob = new RecordingTrack("p2", RecordingTrack.Role.PARTICIPANT);
		bob.setParty(new RecordingParty("bob", "agent", "Bob"));
		bob.setOffsetMillis(8_430L);
		bob.setDurationMillis(11_570L);
		bob.setRate(8000);
		bob.setChannels(1);
		bob.setLevelDbfs(-31.2);
		bob.setClock(new TrackClock(8000).anchor(1_000L, EPOCH.plusMillis(8_430), "rtcp-sr"));
		bob.addSegment(new MediaSegment(0, 8_430L, 11_570L, "seg-0000.m4a"));
		manifest.addTrack(bob);

		TranscriptRef transcript = new TranscriptRef("t1", "en-US", TranscriptRef.Attribution.PER_TRACK);
		transcript.setRedaction(TranscriptRef.Redaction.REDACTED);
		transcript.setEngine("whisper");
		transcript.setModel("large-v3");
		transcript.setModelVersion("3.1");
		transcript.setCreatedUtc(EPOCH.plusSeconds(25).toString());
		transcript.setComplete(true);
		transcript.setObject("transcripts/t1.json");
		transcript.getSource().add("p2");
		manifest.addTranscript(transcript);

		return manifest;
	}

	@Test
	public void roundTripsThroughAMapperThatKnowsNothingAboutUs() throws Exception {
		ConversationManifest before = conference();

		String text = json.writeValueAsString(before);
		ConversationManifest after = json.readValue(text, ConversationManifest.class);

		assertEquals(ConversationManifest.SCHEMA, after.getSchema());
		assertEquals("conv-1", after.getConversation());
		assertEquals("call-1", after.getCall());
		assertEquals(EPOCH, after.epoch());
		assertEquals(2, after.getTracks().size());
		assertEquals(1, after.getTranscripts().size());
		assertEquals("cardiology", after.getAttributes().get("department"));
		assertEquals(ConversationManifest.Timeline.PRESERVED, after.getTimeline());
		assertEquals(-28.0, after.track("mix").getLevelDbfs(), 0.0001);
		assertEquals(8_430L, after.track("p2").getOffsetMillis());
		assertEquals("bob", after.track("p2").getParty().getId());
		assertEquals(TranscriptRef.Attribution.PER_TRACK, after.getTranscripts().get(0).getAttribution());
	}

	/// Timestamps are written as ISO-8601 strings on purpose: a manifest read by
	/// a mapper without a time module must not come back as an epoch number.
	@Test
	public void timestampsAreStoredAsReadableStrings() throws Exception {
		String text = json.writeValueAsString(conference());

		assertTrue(text.contains("\"epochUtc\":\"2026-09-07T04:11:01.474Z\""), text);
		assertFalse(text.contains("\"epochUtc\":1"), "an epoch number would defeat the point");
	}

	@Test
	public void addingATrackRecordsThatItWasIntended() {
		ConversationManifest manifest = new ConversationManifest("conv-1", "call-1", EPOCH);
		manifest.addTrack(new RecordingTrack("p1", RecordingTrack.Role.PARTICIPANT));

		assertEquals(1, manifest.getTracksExpected().size());
		assertEquals("p1", manifest.getTracksExpected().get(0));
		assertNotNull(manifest.track("p1"));
	}

	/// Two-party stereo is a channel map on one track, not a second track, and
	/// it has to survive storage.
	@Test
	public void stereoIsExpressedAsAChannelMap() throws Exception {
		ConversationManifest manifest = new ConversationManifest("conv-1", "call-1", EPOCH);
		RecordingTrack stereo = new RecordingTrack("qm", RecordingTrack.Role.MIX);
		stereo.setChannels(2);
		stereo.getChannelMap().add("alice");
		stereo.getChannelMap().add("bob");
		manifest.addTrack(stereo);

		ConversationManifest after = json.readValue(json.writeValueAsString(manifest), ConversationManifest.class);

		assertEquals(2, after.track("qm").getChannels());
		assertEquals("alice", after.track("qm").getChannelMap().get(0));
		assertEquals("bob", after.track("qm").getChannelMap().get(1));
	}

	/// A pause costs no stored audio. The gap accounts for the time; the
	/// segments do not carry it.
	@Test
	public void aPauseIsRepresentedRatherThanStored() {
		RecordingTrack track = new RecordingTrack("p1", RecordingTrack.Role.PARTICIPANT);
		track.setDurationMillis(20_000L);
		track.addSegment(new MediaSegment(0, 0L, 8_000L, "seg-0000.m4a"));
		track.addSegment(new MediaSegment(1, 12_000L, 8_000L, "seg-0001.m4a"));
		track.addGap(new MediaGap(8_000L, 12_000L, MediaGap.Reason.PCI));

		assertEquals(16_000L, track.recordedMillis());
		assertEquals(12_000L, track.getSegments().get(1).getStartMillis());
		assertEquals(20_000L, track.getSegments().get(1).endMillis());
	}

	@Test
	public void aCardEntryGapIsSuppressionNotAFault() {
		assertFalse(new MediaGap(0L, 1L, MediaGap.Reason.PCI).isFault());
		assertFalse(new MediaGap(0L, 1L, MediaGap.Reason.HOLD).isFault());
		assertTrue(new MediaGap(0L, 1L, MediaGap.Reason.LOSS).isFault());
		assertTrue(new MediaGap(0L, 1L, MediaGap.Reason.FAILOVER).isFault());
	}

	@Test
	public void unknownFieldsFromANewerWriterDoNotBreakAnOlderReader() throws Exception {
		String text = "{\"schema\":\"blade.recording.manifest/1\",\"conversation\":\"conv-1\","
				+ "\"epochUtc\":\"2026-09-07T04:11:01.474Z\",\"somethingAddedLater\":42}";

		ObjectMapper lenient = new ObjectMapper()
				.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		ConversationManifest after = lenient.readValue(text, ConversationManifest.class);

		assertEquals("conv-1", after.getConversation());
		assertEquals(EPOCH, after.epoch());
	}
}
