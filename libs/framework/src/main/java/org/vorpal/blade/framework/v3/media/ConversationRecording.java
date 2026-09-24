package org.vorpal.blade.framework.v3.media;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.Conversations;
import org.vorpal.blade.framework.v3.media.manifest.ManifestStore;
import org.vorpal.blade.framework.v3.media.manifest.MediaSegment;
import org.vorpal.blade.framework.v3.media.manifest.RecordingTrack;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// One conversation's record, from the moment its recorder starts to the moment it is committed:
/// the manifest, the transcript stored beside it, and the release of the recording's destination.
///
/// Any application that records a mix uses it the same way, a two-party call and a meeting alike:
/// start the recorder with [MediaCallflow#record] on a destination from
/// [MediaCallflow#conversationUri], [#open] this with the same destination and attributes, hand
/// [#transcript] to [Hearing#start] as its ear, and [#close] it when the conversation ends.
///
/// ## One track
///
/// The recorder taps the mix: a hub port that contributes nothing receives every other
/// participant, so one recorder captures the whole conversation however many parties join.
/// Per-leg recording would add a track each and is the same format.
///
/// No clock anchor is written. Anchoring a track to absolute time needs the RTP-to-NTP
/// correspondence from an RTCP sender report, which the application does not see. The epoch and
/// the track's own offsets place a lone track correctly; inventing an anchor that was never
/// observed would be worse than recording that there is not one.
///
/// ## Closing is ordered
///
/// [#close] detaches the record at once, so the next conversation on the same media starts
/// clean, then on a pool of its own: stops the media (the recorder's closing flush rides the stop),
/// reads back what the recorder stored, commits the manifest, and only then releases the
/// destination. Revoking the destination first pulls it out from under the writer and loses the
/// last segment and the manifest. None of it runs on the signalling thread: a stop and a release
/// are round trips to the media server, and measured on a rig they held a BYE for over a second.
public final class ConversationRecording {

	/// The id of the transcript heard live. A later re-run with a better model appends another.
	public static final String LIVE_TRANSCRIPT = "live";

	/// Where closing runs: a sequence of blocking round trips, so not the common pool, which is sized
	/// for computation. Daemon threads, so a redeploy is not held open by one.
	private static final ExecutorService TEARDOWN = Executors.newFixedThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "conversation-teardown");
		thread.setDaemon(true);
		return thread;
	});

	/// Where utterances are written: each is a blocking round trip to object storage, and the thread
	/// that delivers one is the media client's, which every media event on the node shares. Order
	/// does not matter, because the sequence is assigned on arrival and names the object.
	private static final ExecutorService STORE = Executors.newFixedThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "conversation-transcript");
		thread.setDaemon(true);
		return thread;
	});

	private final URI destination;
	private final ConversationManifest manifest;
	private final long startedAtMillis = System.currentTimeMillis();
	private final AtomicInteger utterances = new AtomicInteger();
	private volatile TranscriptRef transcript;

	private ConversationRecording(URI destination, ConversationManifest manifest) {
		this.destination = destination;
		this.manifest = manifest;
	}

	/// Describe a conversation whose recorder has just started writing to `destination`, and put its
	/// manifest in the scratchpad. A manifest that cannot be written is logged, and the recording goes
	/// on: the audio is already being written, and losing the call to protect its description would
	/// be the wrong trade. A sweep finalises what the scratchpad holds.
	public static ConversationRecording open(URI destination, Map<String, String> attributes) {
		ConversationManifest manifest = null;
		try {
			manifest = new ConversationManifest(destination.getSchemeSpecificPart(), attributes.get("call"),
					Instant.now());
			manifest.getAttributes().putAll(attributes);
			RecordingTrack mix = new RecordingTrack("mix", RecordingTrack.Role.MIX);
			mix.setOffsetMillis(0L);
			manifest.addTrack(mix);
			ManifestStore store = ManifestStore.installed();
			if (store != null) {
				store.put(manifest);
			}
		} catch (Exception e) {
			log("could not open the manifest: " + e);
		}
		return new ConversationRecording(destination, manifest);
	}

	/// The destination the recorder writes to.
	public URI destination() {
		return destination;
	}

	/// The manifest, for a note the application adds while the conversation runs (a gap, say). Null
	/// when it could not be opened.
	public ConversationManifest manifest() {
		return manifest;
	}

	/// Milliseconds since the recorder started: the conversation's own clock.
	public long millis() {
		return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
	}

	/// An ear that stores each utterance decoded for the record as its own object beside the
	/// recording, and names the transcript in the manifest. Null, with a warning, when no
	/// [TranscriptArchive] is installed: the conversation is then recorded without a transcript.
	///
	/// `redacted` marks the transcript as one a reader is shown redacted unless they hold
	/// `phi:unredact`; the verbatim text is stored either way.
	public Hearing.Ear transcript(boolean redacted) {
		final TranscriptArchive archive = TranscriptArchive.installed();
		if (manifest == null) {
			return null;
		}
		if (archive == null) {
			warn("no TranscriptArchive is installed, so " + manifest.getConversation()
					+ " is recorded without a transcript");
			return null;
		}
		final String conversation = manifest.getConversation();
		TranscriptRef ref = new TranscriptRef(LIVE_TRANSCRIPT, "en-US", TranscriptRef.Attribution.PER_TRACK);
		ref.getSource().add("mix");
		ref.setRedaction(redacted ? TranscriptRef.Redaction.REDACTED : TranscriptRef.Redaction.VERBATIM);
		ref.setObject("transcript/" + LIVE_TRANSCRIPT + "/");
		ref.setCreatedUtc(Instant.now().toString());
		ref.setComplete(false);
		transcript = ref;
		manifest.addTranscript(ref);
		try {
			ManifestStore store = ManifestStore.installed();
			if (store != null) {
				store.put(manifest);
			}
		} catch (Exception e) {
			warn("the manifest for " + conversation + " does not yet mention its transcript: " + e);
		}
		return new Hearing.Ear() {
			@Override
			public void heard(String party, Utterance utterance, boolean live) {
				if (live) {
					return;
				}
				utterance.setSequence(utterances.incrementAndGet());
				if (ref.getEngine() == null) {
					ref.setEngine(utterance.getEngine());
					ref.setModel(utterance.getModel());
				}
				STORE.execute(() -> {
					try {
						archive.append(conversation, LIVE_TRANSCRIPT, utterance);
					} catch (Exception e) {
						log("utterance " + utterance.getSequence() + " of " + conversation + " was not stored: " + e);
					}
				});
			}
		};
	}

	/// End the conversation: run `stopMedia` (may be null when the caller already stopped it), then
	/// commit the manifest and release the destination, in that order, off the calling thread.
	public void close(Runnable stopMedia) {
		TEARDOWN.execute(() -> {
			// Throwable, not Exception: an executor drops whatever a task throws, so an Error here
			// would vanish without a line and the recording would simply never be committed.
			try {
				if (stopMedia != null) {
					stopMedia.run();
				}
				commit();
				MediaCallflow.releaseRecording(destination);
			} catch (Throwable t) {
				log("closing " + destination + " failed: " + t);
			}
		});
	}

	/// Run `task` on the same pool [#close] uses, for media teardown with nothing to commit.
	public static void teardown(Runnable task) {
		TEARDOWN.execute(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				log("teardown failed: " + t);
			}
		});
	}

	/// Close the manifest and commit it, which is the moment the conversation becomes a record.
	/// If it fails the scratchpad entry stays behind and a sweep finalises it.
	private void commit() {
		if (manifest == null) {
			return;
		}
		try {
			long duration = millis();
			manifest.setDurationMillis(duration);
			RecordingTrack mix = manifest.track("mix");
			if (mix != null) {
				mix.setDurationMillis(duration);
			}
			describeStoredAudio(mix, duration);
			TranscriptRef ref = transcript;
			if (ref != null) {
				// The count is what a reader checks the archive against. Complete means the
				// transcriber ran for the whole conversation; a sweep finalising an abandoned one
				// leaves the scratchpad's false, which is the truth for a node that died.
				ref.setUtterances(utterances.get());
				ref.setComplete(true);
			}
			manifest.setComplete(true);
			Conversations.commit(manifest, System.getProperty("weblogic.Name", "unknown"));
			if (Callflow.getSipLogger() != null) {
				Callflow.getSipLogger().info("ConversationRecording: committed " + manifest.getConversation() + " ("
						+ duration + " ms, " + (ref == null ? "no transcript" : utterances.get() + " utterances") + ")");
			}
		} catch (Exception e) {
			log("could not commit the manifest for " + manifest.getConversation() + "; a sweep will finalise it: "
					+ e);
		}
	}

	/// Fold in what the recorder wrote about itself. The application cannot see how many segments
	/// landed; the sink is the only party that was there. Recorded as one span, because the sink
	/// reports a count and a total, not per-segment durations, and inventing lengths would be
	/// manufactured detail.
	private void describeStoredAudio(RecordingTrack track, long duration) {
		RecordingArchive archive = RecordingArchive.installed();
		if (track == null || archive == null) {
			return;
		}
		try {
			RecordingArchive.RecordingSummary stored = archive.summary(manifest.getConversation());
			if (stored == null) {
				track.setState(RecordingTrack.State.FAILED);
				track.setStateReason("the recorder never closed, so nothing describes this track");
				return;
			}
			MediaSegment span = new MediaSegment(0, 0L, duration, "recording");
			span.setBytes(stored.bytes());
			track.addSegment(span);
			if (!stored.complete()) {
				manifest.setComplete(false);
				manifest.setIncompleteReason("the recorder dropped segments");
			}
		} catch (Exception e) {
			log("could not read back what the recorder stored: " + e);
		}
	}

	private static void warn(String message) {
		if (Callflow.getSipLogger() != null) {
			Callflow.getSipLogger().warning("ConversationRecording: " + message);
		}
	}

	private static void log(String message) {
		if (Callflow.getSipLogger() != null) {
			Callflow.getSipLogger().severe("ConversationRecording: " + message);
		}
	}
}
