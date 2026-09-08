package org.vorpal.blade.services.recorder;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.media.mscontrol.MediaEventListener;
import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.RecordingArchive;
import org.vorpal.blade.framework.v3.media.TranscriberEvent;
import org.vorpal.blade.framework.v3.media.manifest.ContextBias;
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.Conversations;
import org.vorpal.blade.framework.v3.media.manifest.ManifestStore;
import org.vorpal.blade.framework.v3.media.manifest.MediaSegment;
import org.vorpal.blade.framework.v3.media.manifest.RecordingTrack;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// Puts the media server in the middle of a call that would otherwise pass
/// through, and records what crosses it.
///
/// ## Why anchoring is not optional
///
/// A B2BUA that relays SDP never sees a media packet, so there is nothing to
/// record. Recording means terminating the caller's media on the media server
/// and offering the media server to the callee, which is two negotiations
/// instead of one relayed pair.
///
/// Anchoring at call setup rather than part way through is also what makes the
/// recording whole. Inserting a media server into a call already in progress is
/// a fresh offer and answer on both legs, so there is an audible gap, and
/// everything said before that moment was never captured. For a recording kept
/// to satisfy an obligation, a missing opening is the part that matters.
///
/// ## Order of operations
///
/// 1. Answer the caller from the media server, and hold that answer.
/// 2. Ask the media server for an offer, and send it to the callee.
/// 3. Apply the callee's answer and start recording; both legs were put on the
///    mixer before any of this.
///
/// Step 1 finishes before the outbound INVITE goes out, which is why
/// [RecorderServlet] defers the send. The caller's answer is known early and
/// handed back unchanged when the callee answers, so the response to the caller
/// never waits on the media server a second time.
///
/// ## What is node-local, and what is not
///
/// The live 309 objects are not serializable, so [#LIVE] is node-local and a
/// failover rebuilds rather than migrates, exactly as `proto/player` does. What
/// does survive is the recording's identity and its classification, because
/// those were written to the store when the conversation began.
public class RecorderAnchor extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	/// The live media for each call, keyed by application-session id.
	public static final Map<String, Anchor> LIVE = new ConcurrentHashMap<>();

	/// One call's media, and the conversation currently recording on it.
	public static final class Anchor {
		public MediaSession ms;
		public NetworkConnection caller;
		public NetworkConnection callee;
		public volatile MediaGroup mg;

		/// The hub both legs meet through. See begin() for why they are not
		/// connected to each other.
		public MediaMixer mixer;

		/// The media server's answer to the caller, produced before the callee
		/// was even called and handed back when the callee answers.
		public volatile byte[] answerForCaller;

		/// The logical destination the current conversation records to, so a
		/// boundary and teardown can release it. Null when nothing is recording.
		public volatile URI recording;

		/// What this conversation's recording is, as it will be stored. Held
		/// while the call is up and committed when it ends. Node-local like the
		/// rest of this object: the scratchpad copy is what survives a failover,
		/// and a sweep finalises it if this node does not come back.
		public volatile ConversationManifest manifest;

		/// When recording started, for the conversation's own duration.
		public volatile long startedAtMillis;

		/// The transcript being written for the current conversation, or null
		/// when none is. Its utterance count is finalised when the manifest
		/// closes; until then the archive's listing is the count.
		public volatile TranscriptRef transcript;

		/// Next utterance sequence, assigned on arrival so the stored name is
		/// stable whatever order the writes complete in.
		public final AtomicInteger utterances = new AtomicInteger();

		/// The listener on the driver's transcriber, kept so a boundary can take
		/// it off again. Left in place it would hear the next conversation too.
		public volatile MediaEventListener<TranscriberEvent> hearing;

		/// The direction the caller asked for in a re-INVITE still being
		/// answered, so the response can carry its mirror. Null between
		/// re-INVITEs.
		public volatile org.vorpal.blade.framework.v3.media.MediaDirection reinviteDirection;

		/// How many times each leg's SDP has been re-issued, for the version.
		public final AtomicInteger reissued = new AtomicInteger();
	}

	/// The media server's SDP for `leg`, re-issued with `direction`, or null
	/// when the leg has not been negotiated. See [AnchoredSdp].
	static byte[] anchoredSdp(Anchor anchor, NetworkConnection leg, org.vorpal.blade.framework.v3.media.MediaDirection direction) {
		if (leg == null) {
			return null;
		}
		try {
			byte[] current = leg.getSdpPortManager().getMediaServerSessionDescription();
			return AnchoredSdp.rewrite(current, direction, anchor.reissued.get());
		} catch (Exception e) {
			sipLogger.warning("RecorderAnchor: could not re-issue the media server's SDP: " + e);
			return null;
		}
	}

	/// The one transcript each conversation gets while it runs. A later re-run
	/// with a better model appends another id; this one is what was heard live.
	static final String LIVE_TRANSCRIPT = "live";

	/// Never dispatched. This callflow is driven directly by the servlet; the
	/// media verbs it inherits stash their continuations on the application
	/// session, so they do not need this instance to survive.
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
	}

	/// Terminate the caller's media on the media server, then produce the offer
	/// that goes to the callee.
	///
	/// `onCalleeOffer` receives the SDP to put in the outbound INVITE. It runs on
	/// a media thread under the application-session lock, so it is safe to send
	/// the request from inside it.
	public void begin(SipApplicationSession app, byte[] callerOffer, Callback<byte[]> onCalleeOffer)
			throws MsControlException {

		MediaSession ms = createMediaSession(app);
		Anchor anchor = new Anchor();
		anchor.ms = ms;
		anchor.caller = ms.createNetworkConnection(NetworkConnection.BASIC);
		anchor.callee = ms.createNetworkConnection(NetworkConnection.BASIC);
		LIVE.put(app.getId(), anchor);

		// The two legs meet through a mixer so that one recorder captures both
		// parties. A recorder tapping a single leg receives only that party.
		//
		// The cost is the mix itself. Each leg carries 8kHz mono; the hub's output
		// is 48kHz stereo, and mixing discards which party said what. Tapping each
		// leg with its own recorder is the alternative: it keeps the parties on
		// separate channels, at the rate they arrive, with no hub in the call. It
		// also makes a conversation two stored objects rather than one, which the
		// archive and the review API would have to carry.
		//
		// Bridging the legs to each other and tapping one of them also works. An
		// earlier note here claimed it did not; that was a dead test call, not a
		// property of the media server. See the javadoc on [#startRecording].
		anchor.mixer = ms.createMediaMixer(MediaMixer.AUDIO);
		join(anchor.caller, Joinable.Direction.DUPLEX, anchor.mixer);
		join(anchor.callee, Joinable.Direction.DUPLEX, anchor.mixer);

		if (callerOffer == null || callerOffer.length == 0) {
			// Late media: the caller offered nothing, so the media server offers
			// first in both directions.
			generateOffer(anchor.callee, calleeOffer -> onCalleeOffer.accept(calleeOffer.getMediaServerSdp()));
			return;
		}

		offer(anchor.caller, callerOffer, callerAnswer -> {
			anchor.answerForCaller = callerAnswer.getMediaServerSdp();
			generateOffer(anchor.callee, calleeOffer -> onCalleeOffer.accept(calleeOffer.getMediaServerSdp()));
		});
	}

	/// Apply the callee's answer and start recording. The legs were put on the
	/// mixer in begin().
	public void connect(SipApplicationSession app, byte[] calleeAnswer, RecorderSettings cfg)
			throws MsControlException {

		Anchor anchor = LIVE.get(app.getId());
		if (anchor == null) {
			return;
		}
		// The legs were bridged in begin(); this only applies the callee's answer
		// and starts the recorder.
		processAnswer(anchor.callee, calleeAnswer, applied -> startRecording(app, anchor, cfg));
	}

	/// Begin a conversation's recording on an anchored call.
	///
	/// ## The group taps the mix, not a leg
	///
	/// The recorder is joined to the mixer both legs meet through, so one recorder
	/// captures both parties. Tapping a leg instead records only the party on that
	/// leg, so capturing a conversation that way takes one recorder per leg.
	///
	/// A JSR-309 `MediaGroup` cannot tap two things at once: the driver's `join`
	/// records *which* source the group serves, so joining a second replaces the
	/// first silently. One group therefore means one source, and the mix is the
	/// only single source carrying both parties.
	///
	/// The hub mixes at 48kHz stereo where each leg carries 8kHz mono, and the mix
	/// discards which party spoke. Per-leg recorders keep both, at the cost of two
	/// stored objects per conversation.
	///
	/// ## A trap, if you are testing this
	///
	/// A leg that never receives RTP stalls the topology it belongs to, and the
	/// recording comes out the right length and completely silent. Both parties
	/// must actually send media, and a recording is only proven by measuring its
	/// level, never by its size or duration.
	void startRecording(SipApplicationSession app, Anchor anchor, RecorderSettings cfg) {
		if (cfg == null || !cfg.isRecord()) {
			return;
		}
		try {
			if (anchor.mg == null) {
				anchor.mg = anchor.ms.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR);
				join(anchor.mg, Joinable.Direction.DUPLEX, anchor.mixer);
			}
			URI destination = MediaCallflow.conversationUri(app);
			anchor.recording = destination;
			Map<String, String> attributes = MediaCallflow.recordingAttributes(app, cfg.getRecordAttributes());
			record(anchor.mg, destination, attributes, done -> {
				// The recording runs until a boundary or teardown stops it.
			});
			openManifest(anchor, destination, attributes);
			if (cfg.isTranscribe()) {
				startTranscribing(anchor, expectedPhrases(app, cfg));
			}
			sipLogger.info("RecorderAnchor: recording " + destination.getScheme() + ":...");
		} catch (Exception e) {
			// A call that cannot be recorded is still a call. Say so loudly and
			// let it proceed, rather than dropping a conversation to protect a
			// recording that has already failed.
			sipLogger.severe("RecorderAnchor: recording could not be started: " + e);
		}
	}

	/// Stop the current conversation's recording and release its destination.
	///
	/// The destination is released only after the recorder has stopped, because
	/// the closing flush rides the stop: revoking first pulls the capability out
	/// from under the writer and loses the last segment and the manifest.
	static void stopRecording(String appId) {
		Anchor anchor = LIVE.get(appId);
		if (anchor == null) {
			return;
		}
		final Closing closing = beginClose(anchor);
		stopTranscribing(anchor);
		try {
			if (anchor.mg != null) {
				anchor.mg.stop();
			}
		} catch (Exception ignore) {
			// best effort
		}
		final URI destination = anchor.recording;
		anchor.recording = null;
		// The conversation that just ended becomes a record now, not at the end
		// of the call. Its manifest was taken off the anchor above, so the next
		// conversation cannot touch it; the commit and the release are round
		// trips and run off the signalling thread, in the same order teardown
		// uses: the recorder's closing flush rode the stop, so the archive can
		// be read back, and only then is the capability released.
		TEARDOWN.execute(() -> {
			try {
				close(closing);
				MediaCallflow.releaseRecording(destination);
			} catch (Throwable t) {
				sipLogger.severe("RecorderAnchor: closing the previous conversation of " + appId + " failed: " + t);
			}
		});
	}

	/// Release the whole anchor at the end of the call, **off the signalling
	/// thread**.
	///
	/// ## Why this is not done inline
	///
	/// Stopping a recorder and releasing a pipeline are round trips to the media
	/// server, and this runs from `callCompleted`, which the B2BUA invokes
	/// *before* it relays the BYE and answers it. Measured on the rig: a BYE
	/// arrived at 08:12:56.410 and the recorder did not relay it until
	/// 08:12:57.488, **1.077 seconds** later, by which time the caller had already
	/// retransmitted it. The whole gap was this method.
	///
	/// Nothing in the BYE exchange depends on the media being gone, so the two are
	/// separated: the anchor leaves [#LIVE] immediately, which is what makes the
	/// call over as far as signalling is concerned, and the media server is told
	/// afterwards.
	///
	/// ## What must stay in order
	///
	/// Inside the task the sequence is load bearing and unchanged: stop the
	/// recorder, release the session, and only then release the recording's
	/// destination. The recorder's closing flush rides the stop and the release,
	/// so revoking the capability first pulls it out from under the writer and
	/// loses the last segment and the manifest. Moving this off the SIP thread
	/// must not become an excuse to reorder it.
	static void release(String appId) {
		final Anchor anchor = LIVE.remove(appId);
		if (anchor == null) {
			return;
		}
		final Closing closing = beginClose(anchor);
		TEARDOWN.execute(() -> {
			// Catch Throwable, not Exception. An executor drops whatever a task
			// throws, so a NoSuchMethodError or any other Error here vanishes
			// without a line anywhere: the recording simply never gets committed
			// and nothing says why. That has already cost an afternoon once.
			try {
				stopTranscribing(anchor);
				try {
					if (anchor.mg != null) {
						anchor.mg.stop();
					}
				} catch (Exception ignore) {
					// best effort
				}
				try {
					anchor.ms.release();
				} catch (Exception ignore) {
					// best effort
				}
				close(closing);
				// After the media session is gone, so whatever the media server
				// still had to write has been written.
				MediaCallflow.releaseRecording(anchor.recording);
			} catch (Throwable t) {
				sipLogger.severe("RecorderAnchor: teardown failed for " + appId + ": " + t);
				for (StackTraceElement frame : t.getStackTrace()) {
					sipLogger.severe("    at " + frame);
				}
			}
		});
	}

	/// Where media teardown runs.
	///
	/// A small pool of its own rather than the common pool: this work is a
	/// sequence of blocking network round trips, and the common pool is sized for
	/// computation, so enough simultaneous hangups would starve everything else
	/// sharing it. Daemon threads, so a redeploy is not held open by one.
	private static final java.util.concurrent.ExecutorService TEARDOWN =
			java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
				Thread thread = new Thread(runnable, "recorder-teardown");
				thread.setDaemon(true);
				return thread;
			});

	/// Begin the conversation's live transcript.
	///
	/// ## Each party is heard on its own
	///
	/// The recorder taps the mix, and a transcript drawn from a mix has to guess
	/// who spoke. The transcriber does not transcribe the mix: it hears each leg
	/// separately and says which one an utterance came from, so the transcript
	/// is attributed per party even though the audio is stored as one track.
	/// This method turns the driver's name for a leg into `caller` or `callee`,
	/// which is the only thing the driver cannot know.
	///
	/// ## Written as it is heard
	///
	/// Every utterance goes to the [TranscriptArchive] as its own object the
	/// moment it arrives, with a sequence assigned here. The archive is
	/// write-once, so a transcript cannot be one object that grows; and because
	/// the pieces are durable as they land, a node that dies mid-call loses
	/// nothing it already heard. The write leaves the driver's thread first: the
	/// event arrives on the media client's socket thread, which must not block on
	/// object storage.
	///
	/// ## What can be missing, and how it shows
	///
	/// Without a transcriber in the driver or an archive on the classpath the
	/// conversation is recorded without a transcript and the log says so at
	/// warning. An utterance the media server's record pass shed under load
	/// never arrives here at all, by design, and is a gap in the sequence rather
	/// than a line of lesser text.
	/// What this call is likely to contain: the deployment's standing phrases
	/// plus whatever the named session attributes hold for this call, such as
	/// a caller's name a Selector looked up from the number.
	static List<String> expectedPhrases(SipApplicationSession app, RecorderSettings cfg) {
		List<String> phrases = new ArrayList<>();
		if (cfg.getTranscribeHints() != null) {
			phrases.addAll(cfg.getTranscribeHints());
		}
		if (cfg.getTranscribeHintAttributes() != null) {
			for (String name : cfg.getTranscribeHintAttributes()) {
				Object value = (name == null) ? null : app.getAttribute(name);
				if (value != null && !String.valueOf(value).trim().isEmpty()) {
					phrases.add(String.valueOf(value).trim());
				}
			}
		}
		return phrases;
	}

	private void startTranscribing(Anchor anchor, List<String> expected) {
		ConversationManifest manifest = anchor.manifest;
		if (manifest == null) {
			return;
		}
		final ContextBias bias = ContextBias.of(expected);
		TranscriptArchive archive = TranscriptArchive.installed();
		if (archive == null) {
			sipLogger.warning("RecorderAnchor: no TranscriptArchive is installed, so " + manifest.getConversation()
					+ " is recorded without a transcript");
			return;
		}
		final String conversation = manifest.getConversation();
		TranscriptRef ref = new TranscriptRef(LIVE_TRANSCRIPT, "en-US", TranscriptRef.Attribution.PER_TRACK);
		ref.getSource().add("mix");
		ref.setRedaction(TranscriptRef.Redaction.VERBATIM);
		ref.setObject("transcript/" + LIVE_TRANSCRIPT + "/");
		ref.setCreatedUtc(Instant.now().toString());
		ref.setComplete(false);
		anchor.transcript = ref;
		anchor.utterances.set(0);

		MediaEventListener<TranscriberEvent> hearing = event -> {
			Utterance utterance = event.getUtterance();
			utterance.setParty(partyLabel(anchor, event.getParty()));
			utterance.setSequence(anchor.utterances.incrementAndGet());
			// The correction keeps what was heard on the utterance; the
			// recognizer's own biasing, if the driver has any, already ran.
			bias.apply(utterance);
			TranscriptRef current = anchor.transcript;
			if (current != null && current.getEngine() == null) {
				current.setEngine(utterance.getEngine());
				current.setModel(utterance.getModel());
			}
			STORE.execute(() -> {
				try {
					archive.append(conversation, LIVE_TRANSCRIPT, utterance);
				} catch (Exception e) {
					sipLogger.severe("RecorderAnchor: utterance " + utterance.getSequence() + " of " + conversation
							+ " was not stored: " + e);
				}
			});
		};
		if (!transcribe(anchor.mg, hearing)) {
			anchor.transcript = null;
			return;
		}
		anchor.hearing = hearing;
		if (!bias.isEmpty()) {
			expectInTranscript(anchor.mg, expected);
		}
		manifest.addTranscript(ref);
		try {
			ManifestStore store = ManifestStore.installed();
			if (store != null) {
				store.put(manifest);
			}
		} catch (Exception e) {
			sipLogger.warning("RecorderAnchor: the manifest for " + conversation
					+ " does not yet mention its transcript: " + e);
		}
	}

	/// Which party a leg is, from the anchor's own point of view. The driver
	/// names a leg by its connection; this application knows which connection
	/// it offered to whom.
	private static String partyLabel(Anchor anchor, Joinable party) {
		if (party == anchor.caller) {
			return "caller";
		}
		if (party == anchor.callee) {
			return "callee";
		}
		if (party instanceof javax.media.mscontrol.MediaObject
				&& ((javax.media.mscontrol.MediaObject) party).getURI() != null) {
			return ((javax.media.mscontrol.MediaObject) party).getURI().toString();
		}
		return "unknown";
	}

	/// Take the listener off and stop the driver's transcriber, before the group
	/// is stopped. The utterance count is folded into the manifest when it
	/// closes, not here.
	private static void stopTranscribing(Anchor anchor) {
		MediaEventListener<TranscriberEvent> hearing = anchor.hearing;
		anchor.hearing = null;
		if (anchor.mg == null) {
			return;
		}
		try {
			if (hearing != null) {
				org.vorpal.blade.framework.v3.media.Transcriber transcriber = anchor.mg
						.getResource(org.vorpal.blade.framework.v3.media.Transcriber.class);
				if (transcriber != null) {
					transcriber.removeListener(hearing);
				}
			}
			MediaCallflow.stopTranscribing(anchor.mg);
		} catch (Exception e) {
			sipLogger.warning("RecorderAnchor: the transcriber would not stop: " + e);
		}
	}

	/// Where utterances are written.
	///
	/// A pool of its own, like [#TEARDOWN] and for the same reason: each write is
	/// a blocking round trip to object storage, and the thread that delivers an
	/// utterance is the media client's socket thread, which every other media
	/// event on this node shares. Ordering does not matter here, because the
	/// sequence was assigned on arrival and names the object.
	private static final java.util.concurrent.ExecutorService STORE =
			java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
				Thread thread = new Thread(runnable, "recorder-transcript");
				thread.setDaemon(true);
				return thread;
			});

	/// Open this conversation's manifest and put it in the scratchpad.
	///
	/// One track, because this topology records the mix: a hub port that
	/// contributes nothing receives every other participant, so one recorder
	/// captures the whole conversation however many parties join. Per-leg
	/// recording would add a track each and is the same format.
	///
	/// No clock anchor is written. Anchoring a track to absolute time needs the
	/// RTP-to-NTP correspondence from an RTCP sender report, which this
	/// application does not see. The epoch and the track's own offsets place a
	/// lone track correctly; what is missing is drift correction between several
	/// senders, and inventing an anchor that was never observed would be worse
	/// than recording that there is not one.
	///
	/// No level either, for now. The media server measures the audio and nothing
	/// carries the number back yet, so every manifest currently reports
	/// `no-level` and a silent recording would still be invisible. That is the
	/// next thing worth closing.
	private static void openManifest(Anchor anchor, URI destination, Map<String, String> attributes) {
		try {
			String conversation = destination.getSchemeSpecificPart();
			ConversationManifest manifest = new ConversationManifest(conversation,
					attributes.get("call"), Instant.now());
			manifest.getAttributes().putAll(attributes);

			RecordingTrack mix = new RecordingTrack("mix", RecordingTrack.Role.MIX);
			mix.setOffsetMillis(0L);
			manifest.addTrack(mix);

			anchor.manifest = manifest;
			anchor.startedAtMillis = System.currentTimeMillis();

			ManifestStore store = ManifestStore.installed();
			if (store != null) {
				store.put(manifest);
			}
		} catch (Exception e) {
			// A conversation that cannot be described is still a conversation.
			// The audio is already being written, and losing the call to protect
			// its manifest would be the wrong trade.
			sipLogger.severe("RecorderAnchor: could not open the manifest: " + e);
		}
	}

	/// What a conversation leaves behind to be committed once its media has
	/// stopped: the manifest, its transcript, and how many utterances were
	/// stored. Taken off the anchor synchronously, so the next conversation on
	/// the same call starts from a clean anchor while this one is still being
	/// written down.
	static final class Closing {
		final ConversationManifest manifest;
		final TranscriptRef transcript;
		final int utterances;
		final long startedAtMillis;

		Closing(ConversationManifest manifest, TranscriptRef transcript, int utterances, long startedAtMillis) {
			this.manifest = manifest;
			this.transcript = transcript;
			this.utterances = utterances;
			this.startedAtMillis = startedAtMillis;
		}
	}

	/// Detach the current conversation's record from the anchor. Returns a
	/// closing with a null manifest when nothing was recording.
	private static Closing beginClose(Anchor anchor) {
		ConversationManifest manifest = anchor.manifest;
		anchor.manifest = null;
		TranscriptRef transcript = anchor.transcript;
		anchor.transcript = null;
		return new Closing(manifest, transcript, anchor.utterances.get(), anchor.startedAtMillis);
	}

	/// Close the manifest and commit it, which is the moment this conversation
	/// becomes a record.
	///
	/// Runs on the teardown pool after the media has stopped, never on the
	/// signalling thread. If it fails the scratchpad entry stays behind and a
	/// sweep finalises it, which is the whole reason the scratchpad exists.
	private static void close(Closing closing) {
		ConversationManifest manifest = closing.manifest;
		if (manifest == null) {
			return;
		}
		try {
			long duration = Math.max(System.currentTimeMillis() - closing.startedAtMillis, 0);
			manifest.setDurationMillis(duration);
			RecordingTrack mix = manifest.track("mix");
			if (mix != null) {
				mix.setDurationMillis(duration);
			}
			describeStoredAudio(manifest, mix, duration);
			describeTranscript(closing);
			manifest.setComplete(true);
			Conversations.commit(manifest, System.getProperty("weblogic.Name", "unknown"));
		} catch (Exception e) {
			sipLogger.severe("RecorderAnchor: could not commit the manifest for "
					+ manifest.getConversation() + "; a sweep will finalise it: " + e);
		}
	}

	/// Fold in what the transcript came to.
	///
	/// The count is what a reader checks the archive against, the way segment
	/// counts let it check a track. Complete means the transcriber ran for the
	/// whole conversation; a sweep finalising an abandoned conversation leaves
	/// the scratchpad's `false`, which is the truth for a node that died.
	private static void describeTranscript(Closing closing) {
		TranscriptRef ref = closing.transcript;
		if (ref == null) {
			return;
		}
		ref.setUtterances(closing.utterances);
		ref.setComplete(true);
	}

	/// Fold in what the recorder wrote about itself.
	///
	/// The application cannot see how many segments landed or whether any were
	/// dropped; the sink is the only party that was there, and it says so in its
	/// own manifest as it closes. Without this the conversation manifest would
	/// describe a track it never verified, which is the failure this whole format
	/// exists to make impossible: [ManifestCheck] would rightly refuse it.
	///
	/// The segments are recorded as one span rather than enumerated. The sink
	/// reports a count and a total, not per-segment durations, so listing each one
	/// with an invented length would be manufactured detail. One span carries what
	/// is actually known and stays honest about the rest.
	private static void describeStoredAudio(ConversationManifest manifest, RecordingTrack track, long duration) {
		if (track == null) {
			return;
		}
		RecordingArchive archive = RecordingArchive.installed();
		if (archive == null) {
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
			sipLogger.warning("RecorderAnchor: could not read back what the recorder stored: " + e);
		}
	}
}
