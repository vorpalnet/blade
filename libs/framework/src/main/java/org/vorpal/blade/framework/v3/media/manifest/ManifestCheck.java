package org.vorpal.blade.framework.v3.media.manifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Reads a manifest and reports what is wrong with it.
///
/// A recording that is wrong usually looks right. The failure that prompted this
/// class was twenty seconds of digital silence in a file of exactly the expected
/// length and a plausible size, which every listing reported as a success for
/// weeks. Size and duration are not evidence that a recording worked.
///
/// So the checks here are the ones a listing cannot do: is anything silent, is a
/// track missing without explanation, does the audio actually cover the span it
/// claims, can each track be placed on the timeline at all.
///
/// ## It reports, it does not decide
///
/// Every problem carries a severity and a code and nothing here throws. Whether
/// a silent track means "reject this recording" or "flag it for review" is a
/// deployment's call, and an application that wants to refuse can filter on
/// [Problem#isError].
public final class ManifestCheck {

	/// The level below which a track is treated as silent.
	///
	/// Digital silence in 16-bit audio measures about -91 dBFS. -80 leaves room
	/// for a track carrying nothing but dither or a comfort-noise floor, which is
	/// silence for every purpose a reviewer cares about.
	public static final double SILENCE_DBFS = -80.0;

	/// How far segment coverage may fall short of a track's claimed length
	/// before it is reported, in milliseconds. Encoders round at frame
	/// boundaries, so exact agreement is not a realistic requirement.
	public static final long COVERAGE_TOLERANCE_MILLIS = 250;

	private ManifestCheck() {
	}

	/// One thing wrong with a manifest.
	public static final class Problem {

		private final String code;
		private final boolean error;
		private final String where;
		private final String detail;

		Problem(String code, boolean error, String where, String detail) {
			this.code = code;
			this.error = error;
			this.where = where;
			this.detail = detail;
		}

		/// A short stable code, so an operator can alert on one kind of problem
		/// without matching on prose.
		public String code() {
			return code;
		}

		/// True when the recording is not fit for its purpose, false when it is
		/// worth a look but may be legitimate.
		public boolean isError() {
			return error;
		}

		/// The track, transcript or conversation the problem is about.
		public String where() {
			return where;
		}

		public String detail() {
			return detail;
		}

		@Override
		public String toString() {
			return (error ? "ERROR " : "WARN  ") + code + " [" + where + "] " + detail;
		}
	}

	/// Everything wrong with this manifest, most structural first. An empty list
	/// means the manifest describes a recording that looks sound.
	public static List<Problem> check(ConversationManifest manifest) {
		List<Problem> problems = new ArrayList<>();
		if (manifest == null) {
			problems.add(new Problem("no-manifest", true, "conversation", "there is no manifest"));
			return problems;
		}
		String conversation = String.valueOf(manifest.getConversation());

		if (manifest.getEpochUtc() == null) {
			problems.add(new Problem("no-epoch", true, conversation,
					"no epoch, so no offset in this manifest means anything"));
		}
		if (manifest.getTimeline() != ConversationManifest.Timeline.PRESERVED) {
			problems.add(new Problem("timeline-not-preserved", true, conversation,
					"timeline is " + manifest.getTimeline() + "; offsets cannot be trusted"));
		}
		if (!manifest.isComplete() && manifest.getIncompleteReason() == null) {
			problems.add(new Problem("unexplained-incomplete", true, conversation,
					"marked incomplete without saying why"));
		}
		if (manifest.getSyncAccuracyMillis() == null && manifest.getTracks().size() > 1) {
			problems.add(new Problem("no-sync-accuracy", false, conversation,
					"several tracks and no stated alignment accuracy"));
		}

		Set<String> present = new LinkedHashSet<>();
		boolean several = manifest.getTracks().size() > 1;
		for (RecordingTrack track : manifest.getTracks()) {
			present.add(track.getId());
			checkTrack(manifest, track, several, problems);
		}
		for (String expected : manifest.getTracksExpected()) {
			if (!present.contains(expected)) {
				problems.add(new Problem("track-missing", true, expected,
						"expected by the conversation and absent, with no entry explaining it"));
			}
		}
		for (TranscriptRef transcript : manifest.getTranscripts()) {
			checkTranscript(manifest, transcript, present, problems);
		}
		return problems;
	}

	private static void checkTrack(ConversationManifest manifest, RecordingTrack track, boolean several,
			List<Problem> problems) {
		String where = String.valueOf(track.getId());

		if (track.getState() != RecordingTrack.State.RECORDED) {
			// Not a fault by itself. A track skipped on purpose is a legitimate
			// outcome; a track that failed is not, and neither is one that says
			// nothing about which it was.
			if (track.getStateReason() == null) {
				problems.add(new Problem("unexplained-absence", true, where,
						"state is " + track.getState() + " with no reason recorded"));
			}
			if (track.getState() == RecordingTrack.State.FAILED) {
				problems.add(new Problem("track-failed", true, where,
						"failed: " + track.getStateReason()));
			}
			return;
		}

		if (track.getSegments().isEmpty()) {
			problems.add(new Problem("no-segments", true, where, "recorded but holds no segments"));
		}
		if (track.getClock() == null || track.getClock().getSyncPoints().isEmpty()) {
			// Only a fault when there is something to align against. A lone track
			// is placed by the conversation epoch and its own offsets, which the
			// application knows without help. Aligning two parties recorded from
			// different senders is what needs an anchor, and claiming one that was
			// never observed would be worse than admitting it is absent.
			problems.add(new Problem("no-clock-anchor", several, where,
					several
							? "no clock anchor, so this track cannot be aligned against the others"
							: "no clock anchor; the epoch places this track, but nothing corrects for sender drift"));
		}
		if (track.getLevelDbfs() == null) {
			problems.add(new Problem("no-level", false, where,
					"no measured level, so a silent recording here would be invisible"));
		} else if (track.getLevelDbfs() <= SILENCE_DBFS) {
			problems.add(new Problem("silent", true, where,
					"measured " + track.getLevelDbfs() + " dBFS, which is silence"));
		}

		for (MediaGap gap : track.getGaps()) {
			if (gap.getReason() == null) {
				problems.add(new Problem("gap-without-reason", true, where,
						"a gap with no reason cannot be told from lost media"));
			} else if (gap.isFault()) {
				problems.add(new Problem("gap-fault", true, where,
						"media lost from " + gap.getStartMillis() + " to " + gap.getEndMillis()
								+ " (" + gap.getReason() + ")"));
			}
		}

		checkCoverage(track, where, problems);
	}

	/// Does the audio actually account for the span the track claims?
	///
	/// Segment durations plus declared gaps should add up to the track's length.
	/// A shortfall is time that is neither recorded nor explained, which is the
	/// signature of a hole nobody noticed: a failover, or a recorder that stopped
	/// while the call carried on.
	private static void checkCoverage(RecordingTrack track, String where, List<Problem> problems) {
		if (track.getDurationMillis() == null) {
			problems.add(new Problem("no-duration", false, where, "no duration, so coverage cannot be checked"));
			return;
		}
		long covered = 0;
		for (MediaSegment segment : track.getSegments()) {
			covered += (segment.getDurationMillis() == null) ? 0 : segment.getDurationMillis();
		}
		long explained = covered;
		for (MediaGap gap : track.getGaps()) {
			explained += gap.lengthMillis();
		}
		long shortfall = track.getDurationMillis() - explained;
		if (shortfall > COVERAGE_TOLERANCE_MILLIS) {
			problems.add(new Problem("coverage-short", true, where,
					shortfall + "ms of this track is neither recorded nor explained by a gap"));
		}

		// Segments must also not overlap or run backwards: two segments claiming
		// the same moment make every later offset ambiguous.
		long previousEnd = Long.MIN_VALUE;
		for (MediaSegment segment : track.getSegments()) {
			long start = (segment.getStartMillis() == null) ? 0 : segment.getStartMillis();
			if (previousEnd != Long.MIN_VALUE && start < previousEnd) {
				problems.add(new Problem("segments-overlap", true, where,
						"segment " + segment.getN() + " starts at " + start + "ms, before the previous ended at "
								+ previousEnd + "ms"));
			}
			previousEnd = segment.endMillis();
		}
	}

	private static void checkTranscript(ConversationManifest manifest, TranscriptRef transcript, Set<String> tracks,
			List<Problem> problems) {
		String where = "transcript " + transcript.getId();
		for (String source : transcript.getSource()) {
			if (!tracks.contains(source)) {
				problems.add(new Problem("transcript-source-missing", true, where,
						"drawn from track " + source + ", which this conversation does not have"));
			}
		}
		if (transcript.getAttribution() == null) {
			problems.add(new Problem("no-attribution", true, where,
					"does not say whether speakers are known or inferred"));
		}
		if (transcript.getRedaction() == null) {
			problems.add(new Problem("no-redaction-state", true, where,
					"does not say whether protected content was withheld"));
		}
		if (transcript.getModelVersion() == null) {
			problems.add(new Problem("no-model-version", false, where,
					"no model version, so this cannot be compared against a later run"));
		}
		if (!transcript.isComplete()) {
			problems.add(new Problem("transcript-incomplete", false, where,
					"does not cover the whole conversation"));
		}
		// A transcript written live is a prefix holding one object per
		// utterance, and its count is the only thing that lets a reader check
		// the archive holds all of them. A complete one without a count claims
		// coverage nobody can verify; one with a count of zero is a transcript of
		// nothing, which is worth a look for the same reason a silent track is.
		boolean live = transcript.getObject() != null && transcript.getObject().endsWith("/");
		if (live && transcript.isComplete() && transcript.getUtterances() == null) {
			problems.add(new Problem("no-utterance-count", true, where,
					"written live and complete, but does not say how many utterances were stored"));
		} else if (live && transcript.getUtterances() != null && transcript.getUtterances() == 0) {
			problems.add(new Problem("empty-transcript", false, where,
					"no utterances were stored; nothing was heard, or nothing was transcribed"));
		}
	}

	/// Whether anything found is bad enough to treat the recording as unsound.
	public static boolean hasErrors(List<Problem> problems) {
		for (Problem p : problems) {
			if (p.isError()) {
				return true;
			}
		}
		return false;
	}
}
