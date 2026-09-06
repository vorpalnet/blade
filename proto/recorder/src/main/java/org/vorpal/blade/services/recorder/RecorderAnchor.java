package org.vorpal.blade.services.recorder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	}

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

		// The two legs meet through a mixer, not by being connected to each other.
		//
		// Connecting them directly is the obvious way to build a two-party call
		// and it cannot be recorded on this media server. The caller's endpoint
		// would then feed two sinks, the far leg and the recorder, and the leg
		// stops with `streaming stopped, reason not-linked` about 130ms later.
		// Measured, same twenty seconds of audio: bridged directly 1,564 bytes,
		// not bridged at all 24,949.
		//
		// Through the mixer nothing feeds two sinks. Each leg has its own hub
		// port, the recorder has a port of its own, and the hub does the fan-out.
		// The recorder's port also receives the mix of both parties rather than
		// one of them, so the recording is the conversation instead of one side
		// of it.
		//
		// It is not free. A mixer costs roughly four times the CPU per participant
		// against an anchored two-party call, and it destroys the speaker
		// separation that scoring a call for synthetic speech depends on. Both are
		// worth revisiting if the leg-to-leg path is ever fixed; neither is worth
		// a recording that stops after half a second.
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
	/// The recorder is joined to the mixer both legs meet through, so it records
	/// the conversation rather than one side of it, and no element ever feeds two
	/// sinks. See [#begin] for why that matters: tapping a leg directly makes that
	/// leg's endpoint serve both the far leg and the recorder, and it then stops
	/// with `not-linked` about 130ms in.
	///
	/// Measured on the rig, the same twenty-second call: tapping a leg produced
	/// 1,564 bytes across 2 segments; through the mixer, 333,180 bytes across 6,
	/// reassembling to 20.01 seconds of audio.
	///
	/// A JSR-309 `MediaGroup` also cannot tap two things at once. The driver's
	/// `join` records *which* source the group serves, so joining a second replaces
	/// the first silently. That is another reason the mix is the right source: one
	/// join, both parties.
	///
	/// The hub mixes at 48kHz stereo, so a recording is far larger than the 8kHz
	/// mono the call actually carries. Worth revisiting for storage cost.
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
			record(anchor.mg, destination, MediaCallflow.recordingAttributes(app, cfg.getRecordAttributes()),
					done -> {
						// The recording runs until a boundary or teardown stops it.
					});
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
		try {
			if (anchor.mg != null) {
				anchor.mg.stop();
			}
		} catch (Exception ignore) {
			// best effort
		}
		URI destination = anchor.recording;
		anchor.recording = null;
		MediaCallflow.releaseRecording(destination);
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
		TEARDOWN.execute(() -> {
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
			// After the media session is gone, so whatever the media server still
			// had to write has been written.
			MediaCallflow.releaseRecording(anchor.recording);
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
}
