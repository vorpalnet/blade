package org.vorpal.blade.services.listener;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.vorpal.blade.framework.v3.media.CallAnalyzer;
import org.vorpal.blade.framework.v3.media.ConversationRecording;
import org.vorpal.blade.framework.v3.media.Hearing;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.manifest.ContextBias;
import org.vorpal.blade.framework.v3.media.manifest.MediaGap;
import org.vorpal.blade.framework.v3.media.manifest.Redactor;
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;

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
/// [ListenerServlet] defers the send. The caller's answer is known early and
/// handed back unchanged when the callee answers, so the response to the caller
/// never waits on the media server a second time.
///
/// ## What is node-local, and what is not
///
/// The live 309 objects are not serializable, so [#LIVE] is node-local and a
/// failover rebuilds rather than migrates, exactly as `proto/player` does. What
/// does survive is the recording's identity and its classification, because
/// those were written to the store when the conversation began.
public class ListenerAnchor extends MediaCallflow {
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

		/// The conversation recording now, or null when nothing is: its
		/// manifest, its stored transcript and its destination. Replaced at each
		/// conversation boundary; the one it replaces closes on its own.
		public volatile ConversationRecording record;

		/// The direction the caller asked for in a re-INVITE still being
		/// answered, so the response can carry its mirror. Null between
		/// re-INVITEs.
		public volatile org.vorpal.blade.framework.v3.media.MediaDirection reinviteDirection;

		/// How many times each leg's SDP has been re-issued, for the version.
		public final AtomicInteger reissued = new AtomicInteger();

		/// The caller's SIP session, so a mid-dialog request can be told apart
		/// by which party sent it: a request about to go to this session came
		/// from the callee.
		public volatile String callerSessionId;

		/// Whether the re-INVITE being answered came from the caller.
		public volatile boolean reinviteFromCaller = true;

		/// The call's Vorpal-ID as the event bus writes it (eight hex digits), so a
		/// request that names the call can find this anchor.
		public volatile String vorpalId;

		/// Parties brought into the call after it began, by the URI of their leg:
		/// the name the transcript gives each, and the SIP dialog that reaches them.
		public final Map<String, Party> parties = new ConcurrentHashMap<>();

		/// The media server's answer for a leg being rebuilt because its party
		/// moved, completed when the fresh endpoint has negotiated. Null when no
		/// move is in progress.
		public volatile java.util.concurrent.CompletableFuture<byte[]> moveAnswer;
	}

	/// A party brought into a live call ([ListenerAnchor#addParty]).
	public static final class Party {
		public final String label;
		public final NetworkConnection leg;
		public volatile String dialogId;

		Party(String label, NetworkConnection leg) {
			this.label = label;
			this.leg = leg;
		}
	}

	/// Follow a party that moved its media: build it a fresh leg on the media
	/// server, negotiate its new offer there, put the leg on the mix in the
	/// old one's place, and release the old one.
	///
	/// The media server does not renegotiate an endpoint, which is why a
	/// re-INVITE is normally answered with the leg's existing SDP. When the
	/// offer moves the party's address that answer would leave the media
	/// server sending to a dead address and the party hearing nothing. A fresh
	/// endpoint costs one negotiation and a new hub port; the far party sees
	/// nothing, the recorder keeps recording the mix, and the transcriber
	/// follows the mix's membership on its own. The swap is written into the
	/// manifest as a gap on the track, [MediaGap.Reason#MOVED], bounded by the
	/// offer's arrival and the fresh leg's answer.
	///
	/// `answer` completes with the media server's SDP for the fresh leg, which
	/// [ListenerServlet#responseEvent] sends to the party in place of the old
	/// one; the party then sends to the new endpoint.
	void moveLeg(Anchor anchor, boolean callerMoved, byte[] newOffer,
			java.util.concurrent.CompletableFuture<byte[]> answer) {
		moveLeg(anchor, callerMoved, newOffer, answer, null);
	}

	/// As [#moveLeg(Anchor, boolean, byte[], CompletableFuture)], then run
	/// `afterSwap` once the fresh leg is on the mix and the old one is gone.
	/// A new party's conversation starts there: the recorder and transcriber
	/// have to see the mix with the new leg in it, not the old.
	void moveLeg(Anchor anchor, boolean callerMoved, byte[] newOffer,
			java.util.concurrent.CompletableFuture<byte[]> answer, Runnable afterSwap) {
		final long movedAtMillis = conversationMillis(anchor);
		try {
			final NetworkConnection fresh = anchor.ms.createNetworkConnection(NetworkConnection.BASIC);
			final NetworkConnection old = callerMoved ? anchor.caller : anchor.callee;
			join(fresh, Joinable.Direction.DUPLEX, anchor.mixer);
			offer(fresh, newOffer, negotiated -> {
				if (callerMoved) {
					anchor.caller = fresh;
				} else {
					anchor.callee = fresh;
				}
				try {
					anchor.mixer.unjoin(old);
				} catch (Exception e) {
					sipLogger.warning("ListenerAnchor: the moved party's old leg could not leave the mix: " + e);
				}
				try {
					old.release();
				} catch (Exception e) {
					sipLogger.warning("ListenerAnchor: the moved party's old leg could not be released: " + e);
				}
				noteMove(anchor, callerMoved ? "caller" : "callee", movedAtMillis, conversationMillis(anchor),
						AnchoredSdp.mediaAddress(newOffer));
				if (afterSwap != null) {
					try {
						afterSwap.run();
					} catch (RuntimeException e) {
						sipLogger.severe("ListenerAnchor: after the leg swap: " + e);
					}
				}
				answer.complete(negotiated.getMediaServerSdp());
			});
		} catch (Exception e) {
			answer.completeExceptionally(e);
		}
	}

	/// Milliseconds since this conversation's recording started, or 0 before
	/// it has.
	private static long conversationMillis(Anchor anchor) {
		ConversationRecording record = anchor.record;
		return (record == null) ? 0L : record.millis();
	}

	private static void noteMove(Anchor anchor, String party, long fromMillis, long toMillis, String address) {
		ConversationRecording record = anchor.record;
		ConversationManifest manifest = (record == null) ? null : record.manifest();
		if (manifest == null || manifest.getTracks().isEmpty()) {
			return;
		}
		MediaGap gap = new MediaGap(fromMillis, Math.max(fromMillis, toMillis), MediaGap.Reason.MOVED);
		gap.setDetail(party + " moved its media to " + address + "; leg rebuilt");
		manifest.getTracks().get(0).addGap(gap);
		sipLogger.info("ListenerAnchor: " + party + " of " + manifest.getConversation() + " moved its media to "
				+ address + "; leg rebuilt in " + (toMillis - fromMillis) + " ms");
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
			sipLogger.warning("ListenerAnchor: could not re-issue the media server's SDP: " + e);
			return null;
		}
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
		try {
			Long id = org.vorpal.blade.framework.v2.analytics.Analytics.getVorpalId(app);
			anchor.vorpalId = (id == null) ? null : String.format("%08X", id);
		} catch (Throwable ignore) {
			// no correlator: the call cannot be found by a bus request, and nothing else needs it
		}

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
		// property of the media server. See the javadoc on [#startListening].
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
	public void connect(SipApplicationSession app, byte[] calleeAnswer, ListenerSettings cfg)
			throws MsControlException {

		Anchor anchor = LIVE.get(app.getId());
		if (anchor == null) {
			return;
		}
		// The legs were bridged in begin(); this only applies the callee's answer
		// and starts the recorder.
		processAnswer(anchor.callee, calleeAnswer, applied -> startListening(app, anchor, cfg));
	}

	/// Begin listening to a conversation on an anchored call: record it, transcribe
	/// it, publish what is said and score the named voices, as the settings ask.
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
	void startListening(SipApplicationSession app, Anchor anchor, ListenerSettings cfg) {
		if (cfg == null) {
			return;
		}
		boolean analyzing = !CallAnalyzer.installed().isEmpty();
		if (!cfg.needsAudio() && !analyzing) {
			return;
		}
		try {
			if (anchor.mg == null) {
				anchor.mg = anchor.ms.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR);
				join(anchor.mg, Joinable.Direction.DUPLEX, anchor.mixer);
			}
			if (cfg.isRecord()) {
				URI destination = MediaCallflow.conversationUri(app);
				Map<String, String> attributes = MediaCallflow.recordingAttributes(app, cfg.getRecordAttributes());
				for (String party : new String[] { "from", "to" }) {
					Object number = app.getAttribute("listener." + party);
					if (number != null) {
						attributes.putIfAbsent(party, String.valueOf(number));
					}
				}
				record(anchor.mg, destination, attributes, done -> {
					// The recording runs until a boundary or teardown stops it.
				});
				anchor.record = ConversationRecording.open(destination, attributes);
				sipLogger.info("ListenerAnchor: recording " + destination.getScheme() + ":...");
			}
			boolean archive = cfg.isRecord() && cfg.isTranscribe();
			if (archive || cfg.isPublishUtterances() || analyzing) {
				startTranscribing(app, anchor, archive, cfg.isPublishUtterances(), expectedPhrases(app, cfg),
						cfg.isRedact() ? Redactor.of(cfg.getRedactPatterns()).withPhrases(protectedPhrases(app, cfg))
								: Redactor.none());
			}
			scoreVoices(app, anchor, cfg);
		} catch (Exception e) {
			// A call that cannot be heard is still a call. Say so loudly and
			// let it proceed, rather than dropping a conversation to protect a
			// recording or a transcript that has already failed.
			sipLogger.severe("ListenerAnchor: listening could not be started: " + e);
		}
	}

	/// Score the voices the settings name, publishing every window and handing
	/// it to the installed analyzers.
	///
	/// Only the named legs are scored, by their connection URIs, because each
	/// scored party is inference on the media server. The assessment is armed
	/// again at every conversation boundary, which picks up a leg rebuilt for a
	/// new party; it outlives the conversation otherwise and ends when the
	/// media session is released.
	private static void scoreVoices(SipApplicationSession app, Anchor anchor, ListenerSettings cfg) {
		List<URI> legs = new ArrayList<>();
		if (cfg.getScoreVoices() != null) {
			for (String party : cfg.getScoreVoices()) {
				NetworkConnection leg = "caller".equals(party) ? anchor.caller
						: "callee".equals(party) ? anchor.callee : null;
				if (leg != null && leg.getURI() != null) {
					legs.add(leg.getURI());
				}
			}
		}
		Hearing.scoreVoices(app, anchor.mg, legs, uri -> partyLabel(anchor, uri), true, true, null);
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
		ConversationRecording record = anchor.record;
		anchor.record = null;
		stopTranscribing(anchor);
		try {
			if (anchor.mg != null) {
				anchor.mg.stop();
			}
		} catch (Exception ignore) {
			// best effort
		}
		// The conversation that just ended becomes a record now, not at the end
		// of the call. It was taken off the anchor above, so the next
		// conversation cannot touch it; the commit and the release run off the
		// signalling thread, in the order ConversationRecording keeps.
		if (record != null) {
			record.close(null);
		}
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
		hangUpParties(appId, anchor);
		ConversationRecording record = anchor.record;
		anchor.record = null;
		Runnable stopMedia = () -> {
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
		};
		if (record != null) {
			record.close(stopMedia);
		} else {
			ConversationRecording.teardown(stopMedia);
		}
	}

	/// What this call is likely to contain: the deployment's standing phrases
	/// plus whatever the named session attributes hold for this call, such as
	/// a caller's name a Selector looked up from the number.
	static List<String> expectedPhrases(SipApplicationSession app, ListenerSettings cfg) {
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

	/// What this call is known to involve that must not reach a reader without
	/// `phi:unredact`: the per-call phrases, under the attribute names they
	/// came from. A caller's name from `callerName` is redacted as
	/// `[callerName]`, a member identifier from `memberId` as `[memberId]`. The
	/// standing phrases, the company's and the agents' names, are not: they are
	/// the deployment's own, not the caller's.
	static Map<String, String> protectedPhrases(SipApplicationSession app, ListenerSettings cfg) {
		Map<String, String> phrases = new java.util.LinkedHashMap<>();
		if (cfg.getTranscribeHintAttributes() != null) {
			for (String name : cfg.getTranscribeHintAttributes()) {
				Object value = (name == null) ? null : app.getAttribute(name);
				if (value != null && !String.valueOf(value).trim().isEmpty()) {
					phrases.put(name, String.valueOf(value).trim());
				}
			}
		}
		return phrases;
	}

	/// Begin the conversation's transcript.
	///
	/// The transcriber does not transcribe the mix: it hears each leg
	/// separately and says which one an utterance came from, so the transcript
	/// is attributed per party even though the audio is stored as one track.
	/// This application's only part is turning the driver's name for a leg into
	/// `caller` or `callee`, which the driver cannot know; hearing, publishing
	/// and the analyzers are [Hearing]'s, and storing each utterance as it lands
	/// is [ConversationRecording#transcript]'s. Without a transcriber in the
	/// driver or an archive on the classpath the conversation is recorded
	/// without a transcript and the log says so at warning.
	private void startTranscribing(SipApplicationSession app, Anchor anchor, boolean archiving, boolean publish,
			List<String> expected, Redactor redactor) {
		final ContextBias bias = ContextBias.of(expected);
		ConversationRecording record = anchor.record;
		Hearing.Ear toArchive = (archiving && record != null) ? record.transcript(!redactor.isEmpty()) : null;
		if (!Hearing.start(app, anchor.mg, uri -> partyLabel(anchor, uri), bias, redactor, publish, true, toArchive)) {
			return;
		}
		if (!bias.isEmpty()) {
			expectInTranscript(anchor.mg, expected);
		}
	}

	/// Which party a leg is, from the anchor's own point of view. The driver
	/// names a leg by its connection's URI; this application knows which
	/// connection it offered to whom.
	private static String partyLabel(Anchor anchor, String party) {
		if (isLeg(anchor.caller, party)) {
			return "caller";
		}
		if (isLeg(anchor.callee, party)) {
			return "callee";
		}
		Party added = (party == null) ? null : anchor.parties.get(party);
		if (added != null) {
			return added.label;
		}
		return (party != null) ? party : "unknown";
	}

	private static boolean isLeg(Object leg, String party) {
		return party != null && leg instanceof javax.media.mscontrol.MediaObject
				&& ((javax.media.mscontrol.MediaObject) leg).getURI() != null
				&& party.equals(((javax.media.mscontrol.MediaObject) leg).getURI().toString());
	}

	/// Stop the transcription and take its listener off, before the group is
	/// stopped. Left running it would hear the next conversation too. The
	/// utterance count is folded into the manifest when it closes, not here.
	private static void stopTranscribing(Anchor anchor) {
		if (anchor.mg == null) {
			return;
		}
		try {
			Hearing.stop(anchor.mg);
		} catch (Exception e) {
			sipLogger.warning("ListenerAnchor: the transcription would not stop: " + e);
		}
	}

	/// The SIP session attribute that marks a dialog to a party brought into the
	/// call, holding the party's leg URI, so the servlet routes its requests here
	/// rather than to the two-party B2BUA.
	static final String PARTY = "org.vorpal.blade.listener.party";

	/// Bring another party into the call: a fresh leg on the call's mixer, an
	/// INVITE to `target` carrying the media server's offer, and the answer
	/// applied when they pick up. The recording, the transcriber and the voice
	/// scoring follow the mix, so the new voice is recorded, transcribed under
	/// `label` and heard by every analyzer with nothing else to arm.
	///
	/// Runs under the application session's lock. The continuations capture only
	/// the application session id and the leg's URI, never the live media
	/// objects, which do not serialize into replicated call state.
	void addParty(SipApplicationSession app, Anchor anchor, String target, String label) throws Exception {
		final NetworkConnection leg = anchor.ms.createNetworkConnection(NetworkConnection.BASIC);
		join(leg, Joinable.Direction.DUPLEX, anchor.mixer);
		final String legUri = leg.getURI().toString();
		anchor.parties.put(legUri, new Party(label, leg));
		final String appId = app.getId();
		final Object from = app.getAttribute("listener.to");
		generateOffer(leg, offered -> {
			SipApplicationSession call = getSipUtil().getApplicationSessionById(appId);
			if (call == null || !call.isValid()) {
				return;
			}
			javax.servlet.sip.Address to = getSipFactory().createAddress(target);
			// From: the number the caller dialled, on the party's own domain, so the
			// party's phone shows which line is calling them in.
			String host = (to.getURI() instanceof javax.servlet.sip.SipURI)
					? ((javax.servlet.sip.SipURI) to.getURI()).getHost() : "localhost";
			javax.servlet.sip.Address fromAddress = getSipFactory().createAddress(
					getSipFactory().createSipURI(from == null ? "listener" : String.valueOf(from), host));
			SipServletRequest invite = getSipFactory().createRequest(call, "INVITE", fromAddress, to);
			invite.setContent(offered.getMediaServerSdp(), "application/sdp");
			invite.getSession().setAttribute(PARTY, legUri);
			sendRequest(invite, response -> partyAnswered(appId, legUri, response));
		});
		sipLogger.info("ListenerAnchor: bringing " + label + " (" + target + ") into " + anchor.vorpalId);
	}

	private void partyAnswered(String appId, String legUri, javax.servlet.sip.SipServletResponse response)
			throws Exception {
		Anchor anchor = LIVE.get(appId);
		Party party = (anchor == null) ? null : anchor.parties.get(legUri);
		if (party == null) {
			return;
		}
		if (response.getStatus() < 200) {
			return;
		}
		if (response.getStatus() >= 300) {
			sipLogger.info("ListenerAnchor: " + party.label + " did not join " + anchor.vorpalId + ": "
					+ response.getStatus() + " " + response.getReasonPhrase());
			dropParty(anchor, legUri);
			return;
		}
		party.dialogId = response.getSession().getId();
		response.createAck().send();
		processAnswer(party.leg, ListenerServlet.bodyOf(response), applied -> {
			// the party is on the mix; everything that follows the mix follows them
		});
		sipLogger.info("ListenerAnchor: " + party.label + " joined " + anchor.vorpalId);
	}

	/// A party left, or never answered: take their leg off the mix.
	static void dropParty(Anchor anchor, String legUri) {
		Party party = (anchor == null || legUri == null) ? null : anchor.parties.remove(legUri);
		if (party == null) {
			return;
		}
		try {
			anchor.mixer.unjoin(party.leg);
		} catch (Exception ignore) {
			// the leg may already be gone with the session
		}
		try {
			party.leg.release();
		} catch (Exception ignore) {
			// best effort
		}
	}

	/// The call is ending: hang up every party still on it. The framework's
	/// teardown ends only the two legs it linked, and a party's dialog is not one
	/// of them.
	private static void hangUpParties(String appId, Anchor anchor) {
		if (anchor.parties.isEmpty()) {
			return;
		}
		SipApplicationSession app = getSipUtil().getApplicationSessionById(appId);
		java.util.Iterator<?> sessions = (app == null || !app.isValid()) ? null : app.getSessions("SIP");
		while (sessions != null && sessions.hasNext()) {
			Object s = sessions.next();
			if (!(s instanceof javax.servlet.sip.SipSession)) {
				continue;
			}
			javax.servlet.sip.SipSession dialog = (javax.servlet.sip.SipSession) s;
			if (dialog.isValid() && dialog.getAttribute(PARTY) != null
					&& dialog.getState() == javax.servlet.sip.SipSession.State.CONFIRMED) {
				try {
					dialog.createRequest("BYE").send();
				} catch (Exception e) {
					sipLogger.warning("ListenerAnchor: could not hang up a party: " + e);
				}
			}
		}
		anchor.parties.clear();
	}

	/// The anchor for the call the event bus names by its Vorpal-ID, on this
	/// node; null when another node, or no node, holds it.
	static Map.Entry<String, Anchor> byVorpalId(String vorpalId) {
		if (vorpalId == null) {
			return null;
		}
		for (Map.Entry<String, Anchor> e : LIVE.entrySet()) {
			if (vorpalId.equalsIgnoreCase(e.getValue().vorpalId)) {
				return e;
			}
		}
		return null;
	}
}
