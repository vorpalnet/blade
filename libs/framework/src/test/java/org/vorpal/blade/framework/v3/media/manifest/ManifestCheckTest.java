package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/// What a manifest has to be able to catch.
///
/// Every case here is one a listing by size and duration reports as a success.
public class ManifestCheckTest {

	private static final Instant EPOCH = Instant.parse("2026-09-07T04:11:01.000Z");

	private ConversationManifest sound() {
		ConversationManifest manifest = new ConversationManifest("conv-1", "call-1", EPOCH);
		manifest.setDurationMillis(20_000L);
		manifest.setSyncAccuracyMillis(20);
		manifest.setComplete(true);

		RecordingTrack track = new RecordingTrack("p1", RecordingTrack.Role.PARTICIPANT);
		track.setParty(new RecordingParty("alice", "caller", "Alice"));
		track.setOffsetMillis(0L);
		track.setDurationMillis(20_000L);
		track.setCodec("aac");
		track.setRate(8000);
		track.setChannels(1);
		track.setLevelDbfs(-28.0);
		track.setClock(new TrackClock(8000).anchor(0L, EPOCH, "rtcp-sr"));
		track.addSegment(new MediaSegment(0, 0L, 20_000L, "seg-0000.m4a"));
		manifest.addTrack(track);
		return manifest;
	}

	private boolean has(List<ManifestCheck.Problem> problems, String code) {
		for (ManifestCheck.Problem p : problems) {
			if (p.code().equals(code)) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void aSoundManifestReportsNothing() {
		List<ManifestCheck.Problem> problems = ManifestCheck.check(sound());
		assertTrue(problems.isEmpty(), "expected no problems, got " + problems);
	}

	/// The failure that prompted all of this: a recording of exactly the right
	/// length and a plausible size, holding nothing.
	@Test
	public void silenceIsCaughtEvenThoughLengthAndSizeLookRight() {
		ConversationManifest manifest = sound();
		manifest.track("p1").setLevelDbfs(-91.0);

		List<ManifestCheck.Problem> problems = ManifestCheck.check(manifest);

		assertTrue(has(problems, "silent"));
		assertTrue(ManifestCheck.hasErrors(problems));
	}

	@Test
	public void aTrackWithNoMeasuredLevelIsFlaggedBecauseSilenceWouldBeInvisible() {
		ConversationManifest manifest = sound();
		manifest.track("p1").setLevelDbfs(null);

		assertTrue(has(ManifestCheck.check(manifest), "no-level"));
	}

	@Test
	public void anExpectedTrackThatNeverAppearedIsAnError() {
		ConversationManifest manifest = sound();
		manifest.getTracksExpected().add("p2");

		List<ManifestCheck.Problem> problems = ManifestCheck.check(manifest);

		assertTrue(has(problems, "track-missing"));
		assertTrue(ManifestCheck.hasErrors(problems));
	}

	/// Absent on purpose and absent through failure must not look the same.
	@Test
	public void aTrackSkippedByPolicyIsNotAFaultButMustSayWhy() {
		ConversationManifest manifest = sound();
		RecordingTrack declined = new RecordingTrack("p2", RecordingTrack.Role.PARTICIPANT);
		declined.setState(RecordingTrack.State.NOT_RECORDED);
		declined.setStateReason("consent withheld");
		manifest.addTrack(declined);

		List<ManifestCheck.Problem> problems = ManifestCheck.check(manifest);
		assertFalse(ManifestCheck.hasErrors(problems), "a deliberate omission is not a fault: " + problems);

		declined.setStateReason(null);
		assertTrue(has(ManifestCheck.check(manifest), "unexplained-absence"));
	}

	@Test
	public void aFailedTrackIsAnError() {
		ConversationManifest manifest = sound();
		RecordingTrack lost = new RecordingTrack("p2", RecordingTrack.Role.PARTICIPANT);
		lost.setState(RecordingTrack.State.FAILED);
		lost.setStateReason("node lost");
		manifest.addTrack(lost);

		assertTrue(has(ManifestCheck.check(manifest), "track-failed"));
	}

	/// A hold accounts for its own time. Time that nothing accounts for is a
	/// hole nobody noticed.
	@Test
	public void aDeclaredGapAccountsForItsTimeButAnUndeclaredOneDoesNot() {
		ConversationManifest manifest = sound();
		RecordingTrack track = manifest.track("p1");
		track.getSegments().clear();
		track.addSegment(new MediaSegment(0, 0L, 8_000L, "seg-0000.m4a"));
		track.addSegment(new MediaSegment(1, 12_000L, 8_000L, "seg-0001.m4a"));

		List<ManifestCheck.Problem> problems = ManifestCheck.check(manifest);
		assertTrue(has(problems, "coverage-short"), "4s unaccounted for: " + problems);

		track.addGap(new MediaGap(8_000L, 12_000L, MediaGap.Reason.HOLD));
		assertFalse(has(ManifestCheck.check(manifest), "coverage-short"));
	}

	@Test
	public void lostMediaIsAFaultWhereAHoldIsNot() {
		ConversationManifest manifest = sound();
		RecordingTrack track = manifest.track("p1");
		track.getSegments().clear();
		track.addSegment(new MediaSegment(0, 0L, 16_000L, "seg-0000.m4a"));
		track.addGap(new MediaGap(16_000L, 20_000L, MediaGap.Reason.LOSS));

		assertTrue(has(ManifestCheck.check(manifest), "gap-fault"));

		track.getGaps().clear();
		track.addGap(new MediaGap(16_000L, 20_000L, MediaGap.Reason.HOLD));
		assertFalse(has(ManifestCheck.check(manifest), "gap-fault"));
	}

	/// One track needs no anchor: the epoch places it. Two tracks recorded from
	/// different senders cannot be aligned without one, so then it is a fault.
	@Test
	public void aMissingClockAnchorMattersOnlyWhenThereIsSomethingToAlignAgainst() {
		ConversationManifest manifest = sound();
		manifest.track("p1").setClock(null);

		List<ManifestCheck.Problem> alone = ManifestCheck.check(manifest);
		assertTrue(has(alone, "no-clock-anchor"));
		assertFalse(ManifestCheck.hasErrors(alone), "a lone track is placed by the epoch: " + alone);

		RecordingTrack second = new RecordingTrack("p2", RecordingTrack.Role.PARTICIPANT);
		second.setDurationMillis(20_000L);
		second.setLevelDbfs(-30.0);
		second.setClock(new TrackClock(8000).anchor(0L, EPOCH, "rtcp-sr"));
		second.addSegment(new MediaSegment(0, 0L, 20_000L, "seg-0000.m4a"));
		manifest.addTrack(second);

		assertTrue(ManifestCheck.hasErrors(ManifestCheck.check(manifest)),
				"two tracks cannot be aligned without an anchor");
	}

	@Test
	public void overlappingSegmentsAreReported() {
		ConversationManifest manifest = sound();
		RecordingTrack track = manifest.track("p1");
		track.getSegments().clear();
		track.addSegment(new MediaSegment(0, 0L, 12_000L, "seg-0000.m4a"));
		track.addSegment(new MediaSegment(1, 8_000L, 12_000L, "seg-0001.m4a"));

		assertTrue(has(ManifestCheck.check(manifest), "segments-overlap"));
	}

	@Test
	public void aTranscriptMustSayHowSpeakersWereDeterminedAndWhetherItIsRedacted() {
		ConversationManifest manifest = sound();
		TranscriptRef transcript = new TranscriptRef("t1", "en-US", null);
		transcript.getSource().add("p1");
		transcript.setComplete(true);
		manifest.addTranscript(transcript);

		List<ManifestCheck.Problem> problems = ManifestCheck.check(manifest);
		assertTrue(has(problems, "no-attribution"));
		assertTrue(has(problems, "no-redaction-state"));
	}

	@Test
	public void aTranscriptCannotCiteATrackTheConversationDoesNotHave() {
		ConversationManifest manifest = sound();
		TranscriptRef transcript = new TranscriptRef("t1", "en-US", TranscriptRef.Attribution.PER_TRACK);
		transcript.setRedaction(TranscriptRef.Redaction.REDACTED);
		transcript.setComplete(true);
		transcript.getSource().add("p9");
		manifest.addTranscript(transcript);

		assertTrue(has(ManifestCheck.check(manifest), "transcript-source-missing"));
	}

	@Test
	public void incompleteWithoutAReasonIsItselfAProblem() {
		ConversationManifest manifest = sound();
		manifest.setComplete(false);

		assertTrue(has(ManifestCheck.check(manifest), "unexplained-incomplete"));

		manifest.setIncompleteReason("node lost at 12s");
		assertFalse(has(ManifestCheck.check(manifest), "unexplained-incomplete"));
	}

	@Test
	public void recordedTimeExcludesGaps() {
		ConversationManifest manifest = sound();
		RecordingTrack track = manifest.track("p1");
		track.addGap(new MediaGap(8_000L, 12_000L, MediaGap.Reason.PCI));

		assertEquals(16_000L, track.recordedMillis());
		assertEquals(20_000L, track.getDurationMillis());
	}

	/// A live transcript is a prefix and a count. Without the count nobody can
	/// check the archive against the manifest; with a count of zero the
	/// conversation was apparently silent.
	@Test
	public void aLiveTranscriptNeedsItsCount() {
		ConversationManifest manifest = sound();
		TranscriptRef live = new TranscriptRef("live", "en-US", TranscriptRef.Attribution.PER_TRACK);
		live.setRedaction(TranscriptRef.Redaction.VERBATIM);
		live.setModelVersion("1");
		live.setObject("transcript/live/");
		live.setComplete(true);
		live.getSource().add(manifest.getTracks().get(0).getId());
		manifest.addTranscript(live);

		assertTrue(codes(ManifestCheck.check(manifest)).contains("no-utterance-count"));
		assertTrue(ManifestCheck.hasErrors(ManifestCheck.check(manifest)));

		live.setUtterances(0);
		assertTrue(codes(ManifestCheck.check(manifest)).contains("empty-transcript"));
		assertFalse(ManifestCheck.hasErrors(ManifestCheck.check(manifest)));

		live.setUtterances(8);
		assertFalse(codes(ManifestCheck.check(manifest)).contains("no-utterance-count"));
		assertFalse(codes(ManifestCheck.check(manifest)).contains("empty-transcript"));

		TranscriptRef whole = new TranscriptRef("t1", "en-US", TranscriptRef.Attribution.DIARIZED);
		whole.setRedaction(TranscriptRef.Redaction.REDACTED);
		whole.setModelVersion("1");
		whole.setObject("transcripts/t1.json");
		whole.setComplete(true);
		whole.getSource().add(manifest.getTracks().get(0).getId());
		manifest.addTranscript(whole);
		assertFalse(codes(ManifestCheck.check(manifest)).contains("no-utterance-count"),
				"a single-object transcript carries no count and is not asked for one");
	}

	private static java.util.List<String> codes(java.util.List<ManifestCheck.Problem> problems) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (ManifestCheck.Problem p : problems) {
			out.add(p.code());
		}
		return out;
	}
}
