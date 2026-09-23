package org.vorpal.blade.framework.v3.media;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import javax.media.mscontrol.MediaEvent;
import javax.media.mscontrol.MediaEventListener;
import javax.media.mscontrol.MediaEventNotifier;
import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.MsControlFactory;
import javax.media.mscontrol.Parameters;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.mediagroup.Player;
import javax.media.mscontrol.mediagroup.PlayerEvent;
import javax.media.mscontrol.mediagroup.Recorder;
import javax.media.mscontrol.mediagroup.RecorderEvent;
import javax.media.mscontrol.mediagroup.signals.SignalDetector;
import javax.media.mscontrol.mediagroup.signals.SignalDetectorEvent;
import javax.media.mscontrol.mediagroup.signals.SpeechRecognitionEvent;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.media.mscontrol.networkconnection.SdpPortManager;
import javax.media.mscontrol.networkconnection.SdpPortManagerEvent;
import javax.media.mscontrol.resource.AllocationEvent;
import javax.media.mscontrol.resource.AllocationEventListener;
import javax.media.mscontrol.resource.AllocationEventNotifier;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import com.bea.wcp.sip.WlssAction;
import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v3.events.AnalyticsEventMapper;

import com.bea.wcp.sip.WlssSipApplicationSession;

import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// A [Callflow] with **JSR-309 media-server verbs written in the lambda-continuation
/// style of [Callflow#sendRequest]** — so a media conversation reads top-to-bottom
/// instead of scattered across `MediaEventListener.onEvent` handlers plus a
/// hand-rolled Coherence cache.
///
/// ## What this replaces
///
/// The traditional JSR-309 app (see the Oracle/USAA `multiparty` conference sample)
/// smears one media conversation across many callbacks and keeps its state by hand
/// in a distributed cache: a singleton `ConferenceManager` holding
/// `com.tangosol.net.NamedCache` replicas, `ParticipantInfo`/`ConferenceSessionInfo`
/// structs cached because the live 309 objects aren't serializable, and every
/// callback rehydrating a throwaway wrapper via
/// `factory.getMediaObject(uri)`. That's the media-plane equivalent of the
/// choose-your-own-adventure SIP handler model BLADE's lambda callflows retired.
///
/// This class does for media what [Callflow#sendRequest] does for SIP: you write
///
/// ```java
/// offer(nc, callerSdp, ans -> {
///     sendResponse(invite.createResponse(200, ans.getMediaServerSdp()));
///     play(mg, greeting, p ->
///         prompt(mg, 4, pin ->                       // collect a 4-digit PIN
///             join(nc, Joinable.Direction.DUPLEX, mixer)));
/// });
/// ```
///
/// and the framework carries the continuation across the media round-trip — and
/// across cluster failover — the same way `sendRequest` carries a response
/// continuation.
///
/// ## How the continuation survives (the mechanism, mirrored from `sendRequest`)
///
/// `sendRequest` stashes its `Callback` as a [javax.servlet.sip.SipSession]
/// attribute keyed by method; the container replicates it (Coherence) and
/// re-invokes it when the response arrives. Media events don't arrive as SIP
/// messages, so we supply the two missing pieces:
///
/// 1. **Binding.** A [MediaSession] is created via [#createMediaSession] (or bound
///    with [#bindMediaSession]), which stamps the owning [SipApplicationSession]'s
///    id onto the MediaSession as an attribute. Every 309 event chains back to its
///    MediaSession (`event.getSource().getMediaSession()`), so from any event we
///    can recover the owning app session.
/// 2. **Continuation store + dispatcher.** Each verb stashes its `Callback` on the
///    **[SipApplicationSession]** (replicated, survives failover — NOT on the
///    MediaSession, whose attributes are driver-local), keyed by MediaSession URI +
///    verb, and registers ONE framework listener ([MediaDispatcher]). When the 309
///    event fires — typically on a media/driver thread, outside the SIP lock — the
///    dispatcher re-enters the app-session lock ([WlssSipApplicationSession#doAction],
///    the same primitive the old sample used for out-of-band sends), pulls the
///    stashed `Callback`, and runs it. Continuations therefore execute under the
///    SAS lock exactly like a SIP continuation, and read/modify replicated call
///    state safely.
///
/// Only serializable state is stored (the `Callback` is [Serializable]; the
/// dispatcher holds only id/uri/verb strings). Live 309 objects are never stored —
/// like `sendRequest` stores no live transaction. Inside a continuation you can
/// re-resolve a live object by URI via [MsControlFactory#getMediaObject].
///
/// ## Verification status
///
/// Compiles and the logic is unit-checkable against the plain 309 interfaces. The
/// one part that can only be proven on a live OCCAS + a real 309 driver is the
/// **lock re-entry on a media/driver thread** ([MediaDispatcher#onEvent]): whether
/// a given driver fires `onEvent` already under the SAS lock (in which case
/// `doAction` is a cheap re-entrant no-op) or on a foreign thread (where `doAction`
/// must acquire it). The JSR-309 media controller driver — which we control — will fire
/// events under the lock; this defensive `doAction` makes the API correct for
/// arbitrary drivers too. **Failover re-attach** is [#reattach]: the continuations
/// survive in the replicated SAS, and the driver rebuilds the live media session from
/// the recovery record it publishes ([#RECOVERY_ATTRIBUTE]).
///
/// ## Nothing but JSR-309 crosses to the driver
///
/// A driver may be deployed apart from the application, in the domain `lib/`, where it
/// shares no classes with BLADE. So everything this class needs from a driver travels
/// through standard `javax.media.mscontrol` calls and string names: the owning session
/// as a [MediaSession] attribute, failover through [MsControlFactory#getMediaObject],
/// media-server loss as an [AllocationEvent], a recorder pause as [Recorder#PAUSE].
/// Never a BLADE type a driver would have to implement.
public abstract class MediaCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	/// [MediaSession] attribute (String): the id of the owning [SipApplicationSession].
	/// Stamped by [#bindMediaSession]; read by [MediaDispatcher] to recover the app
	/// session from any media event.
	public static final String SIP_APP_SESSION_ID = "org.vorpal.blade.v3.media.sasId";

	/// [MediaSession] attribute (String): the name of the application that owns the session, as
	/// [SipApplicationSession#getApplicationName] reports it. Stamped by [#bindMediaSession]; a
	/// driver copies it onto the media server's objects so a party that finds them later, the
	/// media server itself when a control socket dies, knows which application to call back
	/// ([MediaRefresh]).
	public static final String SIP_APP_NAME = "org.vorpal.blade.v3.media.app";

	/// [MediaSession] attribute (String) a driver that supports failover recovery publishes: a URI
	/// that [MsControlFactory#getMediaObject] turns back into this live session on another node.
	/// What the URI holds is the driver's business. BLADE copies it onto the replicated
	/// [SipApplicationSession] whenever media coordinates may have changed, and hands it back in
	/// [#reattach]. A driver that publishes nothing gets no recovery, and [#reattach] returns null.
	public static final String RECOVERY_ATTRIBUTE = "org.vorpal.blade.v3.media.recovery";

	/// [MediaObject] parameter (String value): the id of the [SipApplicationSession] that owns one
	/// resource container — a [NetworkConnection] or [MediaGroup] — when several calls share a
	/// [MediaSession]. Stamped by [#bindMediaObject]; consulted by the verbs before the session-level
	/// binding. `Parameter` is an empty interface in JSR-309, so a framework constant is a legal key.
	public static final javax.media.mscontrol.Parameter SIP_APP_SESSION_ID_PARAMETER = new javax.media.mscontrol.Parameter() {
		@Override
		public String toString() {
			return SIP_APP_SESSION_ID;
		}
	};

	/// Prefix for the [SipApplicationSession] attribute under which a verb stashes
	/// its continuation. Full key is `MEDIA_CB_ + <uri> + ":" + <verb>`, where `<uri>` is the
	/// MediaSession's, or the resource container's when the app bound one ([#bindMediaObject]).
	private static final String MEDIA_CB_ = "org.vorpal.blade.v3.media.cb.";

	/// Prefix for the [SipApplicationSession] marker recording that `<mediaSessionUri>` is bound to
	/// this app session. Full key is `MEDIA_MS_ + <mediaSessionUri>`; [#reattach] scans these to find
	/// the call's media session(s) on the node that takes over after failover.
	private static final String MEDIA_MS_ = "org.vorpal.blade.v3.media.ms.";

	/// Prefix for the [SipApplicationSession] copy of a driver's recovery record. Full key is
	/// `MEDIA_REC_ + <mediaSessionUri>`; see [#RECOVERY_ATTRIBUTE].
	private static final String MEDIA_REC_ = "org.vorpal.blade.v3.media.rec.";

	/// Suffixes on a collect's continuation key: how many digits the [#prompt] wants, and the
	/// out-of-band digits heard so far. See [#deliverDtmf].
	private static final String DIGITS_WANTED = ".wanted";
	private static final String DIGITS_HEARD = ".heard";

	// Verb tags — disambiguate concurrent pending operations on one MediaSession.
	private static final String PLAY = "PLAY";
	private static final String COLLECT = "COLLECT";
	private static final String RECORD = "RECORD";
	private static final String SDP = "SDP";

	/// The 309 factory. Set once at servlet init ([#setMsControlFactory]); the
	/// media-plane analog of the SIP factory the base [Callflow] holds. Volatile:
	/// written on the init thread, read on request/media threads.
	private static volatile MsControlFactory msControlFactory;

	/// Installs the JSR-309 factory the media verbs create sessions from. Called
	/// once from the SIP servlet's init (from the registered driver's
	/// `getFactory(props)`), before any media verb runs.
	public static void setMsControlFactory(MsControlFactory factory) {
		msControlFactory = factory;
	}

	/// The installed 309 factory, or null if none has been set yet.
	public static MsControlFactory getMsControlFactory() {
		return msControlFactory;
	}

	// ============================================================= session setup

	/// Create a [MediaSession] already bound to `app`, so the media verbs can
	/// recover the owning app session from any resulting event. Equivalent to
	/// `factory.createMediaSession()` followed by [#bindMediaSession].
	protected MediaSession createMediaSession(SipApplicationSession app) throws MsControlException {
		MsControlFactory factory = msControlFactory;
		if (factory == null) {
			throw new MsControlException("MediaCallflow: no MsControlFactory set (call setMsControlFactory at init)");
		}
		MediaSession ms = factory.createMediaSession();
		bindMediaSession(ms, app);
		return ms;
	}

	/// Bind an existing [MediaSession] to its owning [SipApplicationSession] — the
	/// prerequisite for using any media verb on resources of this session. Records
	/// the app-session id on the MediaSession (see [#SIP_APP_SESSION_ID]), and a
	/// reverse marker on the (replicated) app session so [#reattach] can find this
	/// media session on the node that takes over after failover.
	protected static void bindMediaSession(MediaSession ms, SipApplicationSession app) {
		ms.setAttribute(SIP_APP_SESSION_ID, app.getId());
		String applicationName = app.getApplicationName();
		if (applicationName != null) {
			ms.setAttribute(SIP_APP_NAME, applicationName);
		}
		app.setAttribute(MEDIA_MS_ + ms.getURI(), ms.getURI().toString());
	}

	/// Bind one resource container — a [NetworkConnection] or [MediaGroup] — to the
	/// [SipApplicationSession] of the call it serves, for the case where several calls
	/// share one [MediaSession]: a conference. In JSR-309 a [javax.media.mscontrol.mixer.MediaMixer]
	/// and every leg joined to it belong to the same MediaSession (one media-server context), so
	/// the session-level binding of [#bindMediaSession] would name only one of the callers. With
	/// this binding, an event on the container's resources — the SDP answer for its leg, a play
	/// completion on its group — continues under *its* caller's app session, and the continuation
	/// is keyed by the container's URI, so concurrent verbs on different legs of one session never
	/// collide.
	///
	/// Unbound containers fall back to the session binding, so single-call apps need nothing new.
	protected static void bindMediaObject(javax.media.mscontrol.MediaObject container, SipApplicationSession app) {
		Parameters p = container.createParameters();
		p.put(SIP_APP_SESSION_ID_PARAMETER, app.getId());
		container.setParameters(p);
	}

	// ==================================================================== verbs

	/// Play `prompts` on `mediaGroup`, then run `onComplete` when playback finishes
	/// (or errors). Mirrors [Callflow#sendRequest]: fire-and-continue. The event
	/// carries success/error ([MediaEvent#isSuccessful], [MediaEvent#getError]).
	protected void play(MediaGroup mediaGroup, URI[] prompts, Callback<PlayerEvent> onComplete)
			throws MsControlException {
		Player player = mediaGroup.getPlayer();
		arm(player, PLAY, onComplete);
		player.play(prompts, null, Parameters.NO_PARAMETER);
	}

	/// Collect up to `numDigits` DTMF signals on `mediaGroup`, then run `onDigits`.
	/// The collected digits are on the event: [SignalDetectorEvent#getSignalString].
	///
	/// The digits may reach the detector from the media plane (RFC 4733 or in-band tones the driver
	/// decodes), or arrive in the signaling plane as a SIP INFO `application/dtmf-relay` body the app
	/// routes in through [#deliverDtmf] / [#deliverInfoDtmf]. Either source completes the same
	/// continuation, so the app writes one `prompt` however the caller's phone carries DTMF.
	protected void prompt(MediaGroup mediaGroup, int numDigits, Callback<SignalDetectorEvent> onDigits)
			throws MsControlException {
		SignalDetector detector = mediaGroup.getSignalDetector();
		Armed armed = arm(detector, COLLECT, onDigits);
		// INFO digits never reach the media server, so they are counted here, beside the
		// continuation they complete. Replicated with it, a half-entered PIN survives a failover.
		armed.app.setAttribute(armed.key + DIGITS_WANTED, numDigits);
		armed.app.removeAttribute(armed.key + DIGITS_HEARD);
		detector.receiveSignals(numDigits, null, null, Parameters.NO_PARAMETER);
	}

	/// Record the mix reaching `mediaGroup` to `destination`, then run `onComplete`.
	///
	/// A `rec:` destination is resolved through the deployment's
	/// [RecordingDestinations] first, so an application names a recording and
	/// never a path. Every other scheme is passed to the driver unchanged, so
	/// `file:` still works and needs nothing installed.
	protected void record(MediaGroup mediaGroup, URI destination, Callback<RecorderEvent> onComplete)
			throws MsControlException {
		record(mediaGroup, destination, java.util.Collections.<String, String>emptyMap(), onComplete);
	}

	/// Record, and say what the recording is.
	///
	/// `attributes` are what an access rule matches on: department, tenant,
	/// queue, agent. They are written down before the first byte of audio, so a
	/// recording is classified while it is still running and stays classified if
	/// the node dies. See [RecordingDestinations#describe] for why that ordering
	/// matters and why the media server is not the thing that writes them.
	///
	/// Describing is best effort and never fails the recording. It fails closed:
	/// attributes that did not get written match no rule that names one, so the
	/// recording is reachable only by a rule with an empty `match`. Refusing to
	/// record at all would be worse, because the alternative to a poorly labelled
	/// recording is usually no evidence.
	protected void record(MediaGroup mediaGroup, URI destination, Map<String, String> attributes,
			Callback<RecorderEvent> onComplete) throws MsControlException {
		Recorder recorder = mediaGroup.getRecorder();
		arm(recorder, RECORD, onComplete);
		URI resolved = resolveDestination(destination);
		describeRecording(destination, attributes);
		recorder.record(resolved, null, Parameters.NO_PARAMETER);
	}

	/// Harvest the recording's attributes from session state.
	///
	/// This is the bridge between the two halves of classifying a call. A
	/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector] derives a
	/// value from the signaling and writes it to session state, which is where a
	/// `TableSelector` lands a department mapped from a dialed number or a queue.
	/// Session state dies with the call, and the access decision happens days
	/// later, so the value has to be copied onto the recording while the call is
	/// still up. That copy is this method.
	///
	/// Names absent from the session are left out rather than stored empty. A
	/// missing attribute and an attribute that is present but blank mean
	/// different things to a rule: the first matches nothing, the second matches
	/// a rule looking for blank.
	///
	/// @param app   the call's session, where selectors wrote
	/// @param names the attribute names to carry onto the recording
	/// @return the attributes that were present, never null
	public static Map<String, String> recordingAttributes(SipApplicationSession app, Collection<String> names) {
		Map<String, String> found = new LinkedHashMap<>();
		if (app == null) {
			return found;
		}

		// The call this recording belongs to, stamped on every recording rather
		// than left to the identifier. A call with a transfer produces several
		// recordings with unrelated ids, and this is what says they were one
		// call. Putting the relationship in an attribute rather than in the
		// object key means a rule can match on it like any other fact, and the
		// store needs no hierarchy to express it.
		Long vorpalId = Analytics.getVorpalId(app);
		if (vorpalId != null) {
			found.put("call", AnalyticsEventMapper.subject(vorpalId, Analytics.getCallStartedAt(app)));
		}

		if (names == null) {
			return found;
		}
		for (String name : names) {
			if (name == null) {
				continue;
			}
			Object value = app.getAttribute(name);
			if (value != null) {
				found.put(name, String.valueOf(value));
			}
		}
		return found;
	}

	/// Write the recording's attributes through the deployment's
	/// [RecordingDestinations]. Never throws: see [#record].
	private static void describeRecording(URI destination, Map<String, String> attributes) {
		RecordingDestinations destinations = RecordingDestinations.installed();
		if (destinations == null || destination == null || attributes == null || attributes.isEmpty()
				|| !RecordingDestinations.SCHEME.equals(destination.getScheme())) {
			return;
		}
		try {
			destinations.describe(destination.getSchemeSpecificPart(), attributes);
		} catch (Exception e) {
			// Loud, because a recording nobody can find later is a quiet failure
			// with a long delay before anyone notices.
			sipLogger.severe("the recording " + destination + " could not be classified, so only a rule with an "
					+ "empty match will reach it: " + e);
		}
	}

	/// The logical name of the recording for this call: `rec:<vorpalId>.<startedAt>`.
	///
	/// The identifier is the pair the rest of BLADE already uses for a call, and
	/// deliberately the same string as the CloudEvents subject and the analytics
	/// correlator, so a recording, an event on the bus and a session row all name
	/// the call identically and nothing has to be joined by guesswork later.
	///
	/// An application passes this to [#record] and never composes a path itself.
	public static URI recordingUri(SipApplicationSession app) {
		Long vorpalId = Analytics.getVorpalId(app);
		if (vorpalId == null) {
			throw new IllegalStateException("this call has no Vorpal-ID, so its recording cannot be named");
		}
		return URI.create(RecordingDestinations.SCHEME + ":"
				+ AnalyticsEventMapper.subject(vorpalId, Analytics.getCallStartedAt(app)));
	}

	/// Stop writing to the recording on `mediaGroup`, keeping it open.
	///
	/// For hold and for a PCI pause. The paused span never reaches the muxer, so
	/// it is not in the file to be found later. A transcription running on the
	/// group ([#transcribe]) pauses with it, for the same reason: a card number
	/// kept out of the audio must not be written down as text.
	///
	/// The pause is JSR-309's own [Recorder#PAUSE] action, and it counts only when the
	/// recorder confirms it with [RecorderEvent#PAUSED] before the action returns. A
	/// driver that ignores the action, or confirms later, is reported as not having
	/// paused. That errs toward telling the operator, which is the safe side for a pause
	/// taken on privacy grounds.
	///
	/// @return true if the recorder confirmed the pause. **False means the passage
	///         was recorded**, and a caller that asked for a pause on privacy grounds
	///         needs to know it did not happen.
	public static boolean pauseRecording(MediaGroup mediaGroup) {
		return setPaused(mediaGroup, true);
	}

	/// Resume writing into the same recording. See [#pauseRecording].
	public static boolean resumeRecording(MediaGroup mediaGroup) {
		return setPaused(mediaGroup, false);
	}

	private static boolean setPaused(MediaGroup mediaGroup, boolean pause) {
		if (mediaGroup == null) {
			return false;
		}
		boolean recorderPaused;
		try {
			Recorder recorder = mediaGroup.getRecorder();
			Confirmation confirmed = new Confirmation(pause ? RecorderEvent.PAUSED : RecorderEvent.RESUMED);
			recorder.addListener(confirmed);
			try {
				mediaGroup.triggerAction(pause ? Recorder.PAUSE : Recorder.RESUME);
			} finally {
				recorder.removeListener(confirmed);
			}
			recorderPaused = confirmed.seen;
			if (!recorderPaused) {
				// Loud, and not an exception. The call is not worth dropping, and
				// the operator has to learn that a pause they configured is not
				// being honoured by the driver they installed.
				sipLogger.warning(pause
						? "this JSR-309 driver's recorder did not confirm the pause, so the passage that was to be "
								+ "left out has been recorded"
						: "this JSR-309 driver's recorder did not confirm resuming, so what follows may be missing "
								+ "from the recording");
			}
		} catch (Exception e) {
			sipLogger.warning("the recorder would not " + (pause ? "pause" : "resume") + ": " + e);
			recorderPaused = false;
		}
		// The transcription follows, independently: a recorder that would not
		// pause is no reason to keep writing the words down.
		Transcription transcription = TRANSCRIPTIONS.get(mediaGroup.getURI().toString());
		if (transcription != null) {
			try {
				transcription.setPaused(pause);
			} catch (Exception e) {
				sipLogger.warning("the transcription would not " + (pause ? "pause" : "resume") + ": " + e);
			}
		}
		return recorderPaused;
	}

	/// The pattern that arms a [SignalDetector] to transcribe rather than collect DTMF.
	///
	/// JSR-309 carries speech recognition on the detector: a `receiveSignals` whose pattern names a
	/// grammar, answered with [SpeechRecognitionEvent]s. BLADE uses that door for transcription, so a
	/// driver needs nothing but the standard interfaces:
	///
	/// - [#transcribe] arms the detector with this value as `SignalDetector.PATTERN[0]`, a signal
	///   count of -1 (no end), and [SignalDetectorEvent#SIGNAL_DETECTED] enabled.
	/// - The phrases from [#expectInTranscript], one per line, ride as `PATTERN[1]`. They arrive on
	///   every arming, and a driver that can bias its recognizer toward them does.
	/// - Each utterance comes back as a `SIGNAL_DETECTED` [SpeechRecognitionEvent] whose
	///   [SpeechRecognitionEvent#getUserInput] is the utterance as JSON, in the shape of [Utterance],
	///   with `party` naming by URI the leg whose audio it was.
	/// - A driver with a fast first decode also sends it, the same way, with `"pass":"live"` in the
	///   JSON. It becomes a [TranscriberEvent.Type#LIVE_UTTERANCE]; everything else is a
	///   [TranscriberEvent.Type#UTTERANCE].
	/// - A pause is [SignalDetector#STOP], and a resume is the same arming again; the driver keeps
	///   the transcription's clock across it. The end is [SignalDetector#CANCEL].
	///
	/// A driver that cannot transcribe refuses the pattern, and [#transcribe] returns false.
	public static final String TRANSCRIPTION_PATTERN = "builtin:transcription";

	/// Transcribe every party on the source `mediaGroup` is joined to, delivering each utterance to
	/// `listener` as it is heard.
	///
	/// Each party is heard separately, so every utterance arrives knowing whose it was, even when the
	/// recording is one mixed track. Utterance bounds are offsets from the moment this was called, so a
	/// transcription started beside [#record] puts its words on the recording's own clock.
	/// [#pauseRecording] pauses the transcription too: what is said during a pause is never heard, and
	/// the clock keeps running, so offsets after the pause stay true.
	///
	/// The listener is node-local and runs on a driver thread. It is not a continuation and is not
	/// stored on the application session: a transcript's durable state is what the listener wrote to
	/// the [org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive], and after a failover the
	/// new node starts a transcription of its own. The group's detector is busy while a transcription
	/// runs on it, so collect DTMF on another group. The wire contract is [#TRANSCRIPTION_PATTERN].
	///
	/// @return true if transcription started. **False means nothing is being transcribed**, because
	///         the installed driver refused, and it is logged at warning so an operator who configured
	///         transcription learns the driver they installed cannot do it.
	public static boolean transcribe(MediaGroup mediaGroup, MediaEventListener<TranscriberEvent> listener) {
		if (mediaGroup == null || listener == null) {
			return false;
		}
		stopTranscribing(mediaGroup);
		String key = mediaGroup.getURI().toString();
		Transcription transcription = null;
		try {
			transcription = new Transcription(mediaGroup, listener);
			transcription.detector.addListener(transcription);
			TRANSCRIPTIONS.put(key, transcription);
			transcription.arm();
			return true;
		} catch (Exception e) {
			if (transcription != null) {
				TRANSCRIPTIONS.remove(key, transcription);
				transcription.detector.removeListener(transcription);
			}
			sipLogger.warning("this JSR-309 driver would not transcribe, so the conversation is recorded without a "
					+ "transcript: " + e);
			return false;
		}
	}

	/// Tell the transcription on `mediaGroup` which names and identifiers this call is likely to
	/// contain, so the recognizer can lean toward them. Replaces any earlier set; empty clears.
	/// Text-level correction against the same phrases is the framework's job, see
	/// [org.vorpal.blade.framework.v3.media.manifest.ContextBias], and works whatever the driver did.
	/// Returns false when nothing is transcribing on the group.
	public static boolean expectInTranscript(MediaGroup mediaGroup, Collection<String> phrases) {
		Transcription transcription = (mediaGroup == null) ? null
				: TRANSCRIPTIONS.get(mediaGroup.getURI().toString());
		if (transcription == null) {
			return false;
		}
		try {
			transcription.expect(phrases);
			return true;
		} catch (Exception e) {
			sipLogger.warning("the transcription would not take the expected phrases: " + e);
			return false;
		}
	}

	/// Stop transcribing on `mediaGroup` and take the listener off. Safe when nothing was transcribing.
	public static void stopTranscribing(MediaGroup mediaGroup) {
		Transcription transcription = (mediaGroup == null) ? null
				: TRANSCRIPTIONS.remove(mediaGroup.getURI().toString());
		if (transcription == null) {
			return;
		}
		transcription.detector.removeListener(transcription);
		try {
			mediaGroup.triggerAction(SignalDetector.CANCEL);
		} catch (Exception e) {
			sipLogger.warning("the transcription would not stop: " + e);
		}
	}

	/// Transcriptions running on this node, by media group URI. Node-local, like the listeners they
	/// carry; an entry leaves when the application stops transcribing.
	private static final java.util.concurrent.ConcurrentHashMap<String, Transcription> TRANSCRIPTIONS =
			new java.util.concurrent.ConcurrentHashMap<>();

	/// One transcription: a group's detector armed with [#TRANSCRIPTION_PATTERN], and the listener that
	/// turns each [SpeechRecognitionEvent] into the application's [TranscriberEvent].
	private static final class Transcription implements MediaEventListener<SignalDetectorEvent> {
		private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
				new com.fasterxml.jackson.databind.ObjectMapper().configure(
						com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		final MediaGroup group;
		final SignalDetector detector;
		private final MediaEventListener<TranscriberEvent> listener;
		private volatile java.util.List<String> phrases = java.util.Collections.emptyList();
		private volatile boolean paused;

		Transcription(MediaGroup group, MediaEventListener<TranscriberEvent> listener) throws MsControlException {
			this.group = group;
			this.detector = group.getSignalDetector();
			this.listener = listener;
		}

		void arm() throws MsControlException {
			Parameters p = group.createParameters();
			p.put(SignalDetector.PATTERN[0], TRANSCRIPTION_PATTERN);
			if (!phrases.isEmpty()) {
				p.put(SignalDetector.PATTERN[1], String.join("\n", phrases));
			}
			p.put(SignalDetector.ENABLED_EVENTS,
					new javax.media.mscontrol.EventType[] { SignalDetectorEvent.SIGNAL_DETECTED });
			detector.receiveSignals(-1, new javax.media.mscontrol.Parameter[] { SignalDetector.PATTERN[0] }, null, p);
		}

		void expect(Collection<String> expected) throws MsControlException {
			java.util.List<String> next = new java.util.ArrayList<>();
			if (expected != null) {
				for (String phrase : expected) {
					if (phrase != null && !phrase.trim().isEmpty()) {
						next.add(phrase.trim());
					}
				}
			}
			phrases = next;
			if (!paused) {
				arm();
			}
		}

		void setPaused(boolean pause) throws MsControlException {
			if (pause == paused) {
				return;
			}
			paused = pause;
			if (pause) {
				group.triggerAction(SignalDetector.STOP);
			} else {
				arm();
			}
		}

		@Override
		public void onEvent(SignalDetectorEvent event) {
			if (!(event instanceof SpeechRecognitionEvent)
					|| !SignalDetectorEvent.SIGNAL_DETECTED.equals(event.getEventType())) {
				return;
			}
			Utterance utterance;
			boolean live;
			try {
				com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(((SpeechRecognitionEvent) event).getUserInput());
				live = "live".equals(tree.path("pass").asText(""));
				if (tree instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
					((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("pass");
				}
				utterance = JSON.treeToValue(tree, Utterance.class);
			} catch (Exception e) {
				sipLogger.warning("an utterance from the JSR-309 driver could not be read and was dropped: " + e);
				return;
			}
			listener.onEvent(new Heard(event.getSource(), utterance,
					live ? TranscriberEvent.Type.LIVE_UTTERANCE : TranscriberEvent.Type.UTTERANCE));
		}
	}

	/// An utterance as the application's listener receives it.
	private static final class Heard implements TranscriberEvent {
		private final SignalDetector source;
		private final Utterance utterance;
		private final TranscriberEvent.Type type;

		Heard(SignalDetector source, Utterance utterance, TranscriberEvent.Type type) {
			this.source = source;
			this.utterance = utterance;
			this.type = type;
		}

		@Override
		public Utterance getUtterance() {
			return utterance;
		}

		@Override
		public SignalDetector getSource() {
			return source;
		}

		@Override
		public javax.media.mscontrol.EventType getEventType() {
			return type;
		}

		@Override
		public boolean isSuccessful() {
			return true;
		}

		@Override
		public javax.media.mscontrol.MediaErr getError() {
			return MediaEvent.NO_ERROR;
		}

		@Override
		public String getErrorText() {
			return null;
		}
	}

	/// The pattern that arms a [SignalDetector] to score voices rather than collect DTMF.
	///
	/// The same door as [#TRANSCRIPTION_PATTERN], and on the same detector, so a driver needs nothing
	/// but the standard interfaces:
	///
	/// - [#assessVoice] arms the detector with this value as `SignalDetector.PATTERN[0]` and the URIs
	///   of the legs to score, one per line, as `PATTERN[1]`; none means every party.
	/// - Each scored window comes back as a `SIGNAL_DETECTED` [SignalDetectorEvent] that is not a
	///   [SpeechRecognitionEvent], whose [SignalDetectorEvent#getSignalString] is JSON:
	///   `{"party":"<leg URI>","score":0.93,"model":"<name>","offsetMillis":41000}`.
	/// - The assessment runs until the group is released. It is not paused with the recording,
	///   because nothing it hears is kept.
	///
	/// A driver that cannot score voices refuses the pattern, and [#assessVoice] returns false.
	public static final String VOICE_ASSESSMENT_PATTERN = "builtin:voice-assessment";

	/// Score the voices of `parties` on the source `mediaGroup` is joined to, delivering each
	/// scored window to `listener`. An empty `parties` scores everyone.
	///
	/// The listener is node-local and runs on a driver thread, like a transcription's; see
	/// [#transcribe]. It can run beside a transcription on the same group.
	///
	/// @return true if the assessment started. False means no voice is being scored, because the
	///         installed driver refused, and it is logged at warning.
	public static boolean assessVoice(MediaGroup mediaGroup, Collection<URI> parties,
			MediaEventListener<VoiceAssessmentEvent> listener) {
		if (mediaGroup == null || listener == null) {
			return false;
		}
		stopAssessingVoice(mediaGroup);
		String key = mediaGroup.getURI().toString();
		Assessment assessment = null;
		try {
			assessment = new Assessment(mediaGroup, listener);
			assessment.detector.addListener(assessment);
			ASSESSMENTS.put(key, assessment);
			Parameters p = mediaGroup.createParameters();
			p.put(SignalDetector.PATTERN[0], VOICE_ASSESSMENT_PATTERN);
			if (parties != null && !parties.isEmpty()) {
				StringBuilder lines = new StringBuilder();
				for (URI party : parties) {
					if (party != null) {
						lines.append(party).append('\n');
					}
				}
				p.put(SignalDetector.PATTERN[1], lines.toString().trim());
			}
			p.put(SignalDetector.ENABLED_EVENTS,
					new javax.media.mscontrol.EventType[] { SignalDetectorEvent.SIGNAL_DETECTED });
			assessment.detector.receiveSignals(-1, new javax.media.mscontrol.Parameter[] { SignalDetector.PATTERN[0] },
					null, p);
			return true;
		} catch (Exception e) {
			if (assessment != null) {
				ASSESSMENTS.remove(key, assessment);
				assessment.detector.removeListener(assessment);
			}
			sipLogger.warning("this JSR-309 driver would not score voices: " + e);
			return false;
		}
	}

	/// Stop delivering scored windows from `mediaGroup`. Safe when nothing was being assessed. The
	/// driver's scoring itself ends when the group is released.
	public static void stopAssessingVoice(MediaGroup mediaGroup) {
		Assessment assessment = (mediaGroup == null) ? null : ASSESSMENTS.remove(mediaGroup.getURI().toString());
		if (assessment != null) {
			assessment.detector.removeListener(assessment);
		}
	}

	/// Assessments running on this node, by media group URI. Node-local, like [#TRANSCRIPTIONS].
	private static final java.util.concurrent.ConcurrentHashMap<String, Assessment> ASSESSMENTS =
			new java.util.concurrent.ConcurrentHashMap<>();

	/// One assessment: the listener that turns each scored window into a [VoiceAssessmentEvent].
	private static final class Assessment implements MediaEventListener<SignalDetectorEvent> {
		private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

		final SignalDetector detector;
		private final MediaEventListener<VoiceAssessmentEvent> listener;

		Assessment(MediaGroup group, MediaEventListener<VoiceAssessmentEvent> listener) throws MsControlException {
			this.detector = group.getSignalDetector();
			this.listener = listener;
		}

		@Override
		public void onEvent(SignalDetectorEvent event) {
			if (event instanceof SpeechRecognitionEvent
					|| !SignalDetectorEvent.SIGNAL_DETECTED.equals(event.getEventType())) {
				return;
			}
			String json = event.getSignalString();
			if (json == null || !json.trim().startsWith("{")) {
				return;
			}
			try {
				com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(json);
				if (!tree.has("score")) {
					return;
				}
				listener.onEvent(new Scored(event.getSource(), tree.path("party").asText(null),
						tree.path("score").asDouble(), tree.path("model").asText(null),
						tree.path("offsetMillis").asLong(0)));
			} catch (Exception e) {
				sipLogger.warning("a voice score from the JSR-309 driver could not be read and was dropped: " + e);
			}
		}
	}

	/// A scored window as the application's listener receives it.
	private static final class Scored implements VoiceAssessmentEvent {
		private final SignalDetector source;
		private final String party;
		private final double score;
		private final String model;
		private final long offsetMillis;

		Scored(SignalDetector source, String party, double score, String model, long offsetMillis) {
			this.source = source;
			this.party = party;
			this.score = score;
			this.model = model;
			this.offsetMillis = offsetMillis;
		}

		@Override
		public String getParty() {
			return party;
		}

		@Override
		public double getScore() {
			return score;
		}

		@Override
		public String getModel() {
			return model;
		}

		@Override
		public long getOffsetMillis() {
			return offsetMillis;
		}

		@Override
		public SignalDetector getSource() {
			return source;
		}

		@Override
		public javax.media.mscontrol.EventType getEventType() {
			return VoiceAssessmentEvent.Type.ASSESSED;
		}

		@Override
		public boolean isSuccessful() {
			return true;
		}

		@Override
		public javax.media.mscontrol.MediaErr getError() {
			return MediaEvent.NO_ERROR;
		}

		@Override
		public String getErrorText() {
			return null;
		}
	}

	/// The name of a **new conversation** within this call.
	///
	/// One call can hold several conversations. A transfer replaces the party on
	/// the far side, and who may hear what changes at that instant, so the
	/// recording changes with it: the application stops the current recording,
	/// releases its destination, and starts a new one named by this.
	///
	/// ## Why a whole recording and not a marked span
	///
	/// A conversation boundary is the line at which the answer to "who may hear
	/// this" changes. Making it a boundary between two recordings lets the
	/// existing per-recording decision do all the work: a billing reviewer is
	/// granted the billing conversation and refused the support one that preceded
	/// it, with an audit record for each. Keeping one recording and marking spans
	/// inside it would mean authorizing a time range and serving a clipped
	/// stream, which is a second access-control mechanism to get right.
	///
	/// This is not the timed chunking that was removed. That split on a clock,
	/// which served no listener and existed only to bound a credential's life.
	/// This splits on the only boundary that means anything to a reviewer.
	///
	/// ## Nothing about this reaches the media server
	///
	/// The media server is told to stop writing here and start writing there. It
	/// has no notion of a conversation, a transfer, or a department, and needs
	/// none. Deciding where one conversation ends is the application's job,
	/// because the application is the only party that knows what the call means.
	///
	/// ## How conversations of one call are found together
	///
	/// Not by the identifier, which is flat and unique per conversation, but by
	/// the `call` attribute that [#recordingAttributes] stamps on every
	/// recording. The relationship is data the policy can match on rather than a
	/// shape in an object key, so a rule can name the call as readily as the
	/// conversation, and the store needs no hierarchy to express it.
	///
	/// The application must release the previous destination at the boundary. A
	/// capability outlives its recording otherwise, until its backstop expiry.
	public static URI conversationUri(SipApplicationSession app) {
		Long vorpalId = Analytics.getVorpalId(app);
		if (vorpalId == null) {
			throw new IllegalStateException("this call has no Vorpal-ID, so its conversation cannot be named");
		}
		return URI.create(RecordingDestinations.SCHEME + ":"
				+ AnalyticsEventMapper.subject(vorpalId, new java.util.Date()));
	}

	/// Release the destination for a recording that has stopped.
	///
	/// Best effort by design: it runs during teardown, where a destination that
	/// outlives its recording is a small bounded problem and an exception is not.
	public static void releaseRecording(URI destination) {
		RecordingDestinations destinations = RecordingDestinations.installed();
		if (destinations == null || destination == null
				|| !RecordingDestinations.SCHEME.equals(destination.getScheme())) {
			return;
		}
		try {
			destinations.release(destination.getSchemeSpecificPart());
		} catch (RuntimeException e) {
			sipLogger.warning("could not release the recording destination for " + destination + ": " + e);
		}
	}

	private static URI resolveDestination(URI destination) throws MsControlException {
		if (destination == null || !RecordingDestinations.SCHEME.equals(destination.getScheme())) {
			return destination;
		}
		RecordingDestinations destinations = RecordingDestinations.installed();
		if (destinations == null) {
			throw new MsControlException("a " + RecordingDestinations.SCHEME
					+ ": destination needs a RecordingDestinations on the classpath");
		}
		try {
			String resolved = destinations.resolve(destination.getSchemeSpecificPart());
			if (resolved == null || resolved.isEmpty()) {
				throw new MsControlException("no destination was produced for " + destination);
			}
			return URI.create(resolved);
		} catch (MsControlException e) {
			throw e;
		} catch (Exception e) {
			throw new MsControlException("could not resolve " + destination, e);
		}
	}

	/// Feed the caller's SDP offer to `networkConnection` and run `onAnswer` when
	/// the media server's answer is ready. The answer bytes are on the event:
	/// [SdpPortManagerEvent#getMediaServerSdp] — hand them straight to your
	/// `200 OK`. This is the 309 side of the 3PCC anchor.
	/// Ask the media server to offer **first**, and run `onOffer` when its offer is ready. The SDP is
	/// on the event: [SdpPortManagerEvent#getMediaServerSdp].
	///
	/// This is the mirror of [#offer] and it is what two things need:
	///
	/// - **Late media** — an INVITE arrives with no SDP, so the offer must go out in the `200 OK`
	///   and the caller's answer comes back in the `ACK`. See [#answerWithLateMedia].
	/// - **Dropping the media server into a call that is already up** — a browser-to-browser call
	///   being escalated to recording or conferencing has to be re-offered from the media server,
	///   because the two endpoints' media is encrypted directly to each other and cannot be tapped.
	///
	/// Completion arrives as [SdpPortManagerEvent#OFFER_GENERATED].
	protected void generateOffer(NetworkConnection networkConnection, Callback<SdpPortManagerEvent> onOffer)
			throws MsControlException {
		SdpPortManager sdp = networkConnection.getSdpPortManager();
		arm(sdp, SDP, onOffer);
		sdp.generateSdpOffer();
		captureRecovery(appOf(networkConnection.getMediaSession()), networkConnection.getMediaSession());
	}

	/// Feed the peer's answer to an offer this media server generated, and run `onProcessed` once the
	/// negotiation is complete ([SdpPortManagerEvent#ANSWER_PROCESSED]).
	protected void processAnswer(NetworkConnection networkConnection, byte[] peerAnswer,
			Callback<SdpPortManagerEvent> onProcessed) throws MsControlException {
		SdpPortManager sdp = networkConnection.getSdpPortManager();
		arm(sdp, SDP, onProcessed);
		sdp.processSdpAnswer(peerAnswer);
	}

	/// Answer an INVITE that arrived with **no SDP**: offer from the media server in the `200 OK`,
	/// and take the caller's answer out of the `ACK`.
	///
	/// Carriers do send SDP-less INVITEs, and a gateway that only knows how to consume an offer
	/// cannot complete the call at all. No new machinery is needed for the ACK —
	/// [org.vorpal.blade.framework.Callflow#sendResponse] already delivers it to a continuation.
	///
	/// @param invite       the SDP-less initial INVITE
	/// @param nc           the media dialog facing the caller
	/// @param onNegotiated run once the answer from the ACK has been applied; may be null
	protected void answerWithLateMedia(SipServletRequest invite, NetworkConnection nc,
			Callback<SipServletRequest> onNegotiated) throws MsControlException {
		answerWithLateMedia(invite, nc, null, onNegotiated);
	}

	/// [#answerWithLateMedia(SipServletRequest, NetworkConnection, Callback)] plus a hook at the
	/// answer itself.
	///
	/// The `200 OK` is built and sent inside this method, so an application that wants to act when
	/// the call is answered — publish a fact, tell a client, add a header — has no moment to do it
	/// in. `onAnswering` is handed that response **before it goes out**, the same arrangement
	/// `B2buaListener.callAnswered` offers ("This response object may be modified before it is sent
	/// back"). Do not send it yourself.
	///
	/// The distinction matters most on this path: `onAnswering` fires when the media server's offer
	/// is on its way to the caller, and `onNegotiated` fires only once the caller's answer has come
	/// back in the `ACK` and been applied. Those are different instants, and on a late-media call
	/// they can be far apart.
	///
	/// @param invite       the SDP-less initial INVITE
	/// @param nc           the media dialog facing the caller
	/// @param onAnswering  run with the `200 OK` just before it is sent; may be null
	/// @param onNegotiated run once the answer from the ACK has been applied; may be null
	protected void answerWithLateMedia(SipServletRequest invite, NetworkConnection nc,
			Callback<SipServletResponse> onAnswering, Callback<SipServletRequest> onNegotiated)
			throws MsControlException {

		generateOffer(nc, offerEvent -> {
			SipServletResponse ok = invite.createResponse(200);
			ok.setContent(offerEvent.getMediaServerSdp(), "application/sdp");
			if (onAnswering != null) {
				onAnswering.accept(ok);
			}
			sendResponse(ok, ack -> {
				byte[] answer = rawContent(ack);
				if (answer != null) {
					processAnswer(nc, answer, processed -> {
						if (onNegotiated != null) {
							onNegotiated.accept(ack);
						}
					});
				} else if (onNegotiated != null) {
					// An ACK with no body is not fatal — some peers answer in a PRACK-negotiated
					// early dialog instead, leaving the dialog as the media server already set it.
					onNegotiated.accept(ack);
				}
			});
		});
	}

	/// True when `request` carries no body — the late-media case for an INVITE.
	protected static boolean isLateMedia(SipServletRequest request) {
		return rawContent(request) == null;
	}

	/// A message's body as bytes, or null when there is none.
	protected static byte[] rawContent(javax.servlet.sip.SipServletMessage message) {
		try {
			Object content = message.getContent();
			if (content == null) {
				return null;
			}
			byte[] bytes = (content instanceof byte[]) ? (byte[]) content
					: content.toString().getBytes(StandardCharsets.UTF_8);
			return bytes.length == 0 ? null : bytes;
		} catch (java.io.IOException e) {
			return null;
		}
	}

	protected void offer(NetworkConnection networkConnection, byte[] callerSdpOffer,
			Callback<SdpPortManagerEvent> onAnswer) throws MsControlException {
		SdpPortManager sdp = networkConnection.getSdpPortManager();
		arm(sdp, SDP, onAnswer);
		sdp.processSdpOffer(callerSdpOffer);
		// The anchor now exists on the media server (processSdpOffer created the pipeline + dialog).
		// Refresh the failover recovery record so a node that takes over between now and the next
		// verb can still reclaim — and release — the running media. Closes the leak window.
		captureRecovery(appOf(networkConnection.getMediaSession()), networkConnection.getMediaSession());
	}

	/// Join two media endpoints (e.g. a caller's [NetworkConnection] to a conference
	/// [javax.media.mscontrol.mixer.MediaMixer]). Synchronous per the 309
	/// [Joinable#join] contract — it returns when the join is established. The
	/// asynchronous [Joinable#joinInitiate] variant (with a `JoinEvent` continuation)
	/// is a future addition; the synchronous form matches how the reference
	/// conference sample joins dialogs.
	///
	/// A mixer and its legs share one [MediaSession]; bind each caller's leg to its own app
	/// session with [#bindMediaObject] before using the other verbs on it.
	protected void join(Joinable from, Joinable.Direction direction, Joinable to)
			throws MsControlException {
		from.join(direction, to);
	}

	// =========================================================== out-of-band DTMF

	/// Deliver out-of-band DTMF (digits that arrived in the signaling plane, typically a SIP INFO) to
	/// `app`'s pending [#prompt].
	///
	/// The digits never reach the media server, so BLADE completes the collect itself and no driver is
	/// involved. They are counted against the prompt's digit count; a `#` ends the collect early and is
	/// not part of the result. When the collect completes, its continuation runs with the digits on
	/// [SignalDetectorEvent#getSignalString], exactly as if the detector had heard them.
	///
	/// Returns true if a prompt was collecting and took the digits, false if none was. `digits` is the
	/// raw DTMF string, e.g. `"5"` or `"1234"`. Call it holding `app`'s lock, as a SIP callflow does.
	public static boolean deliverDtmf(SipApplicationSession app, String digits) {
		if (app == null || digits == null || digits.isEmpty()) {
			return false;
		}
		String key = pendingCollect(app);
		if (key == null) {
			return false;
		}
		Object wanted = app.getAttribute(key + DIGITS_WANTED);
		int target = (wanted instanceof Integer) ? (Integer) wanted : 0;
		Object heard = app.getAttribute(key + DIGITS_HEARD);
		StringBuilder collected = new StringBuilder(heard == null ? "" : heard.toString());
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			if (c == '#') {
				completeCollect(app, key, collected.toString());
				return true;
			}
			collected.append(c);
			if (target > 0 && collected.length() >= target) {
				completeCollect(app, key, collected.toString());
				return true;
			}
		}
		app.setAttribute(key + DIGITS_HEARD, collected.toString());
		return true;
	}

	/// The continuation key of the collect `app` is waiting on, or null when none is.
	private static String pendingCollect(SipApplicationSession app) {
		String suffix = ":" + COLLECT;
		java.util.Iterator<String> names = app.getAttributeNames();
		while (names.hasNext()) {
			String name = names.next();
			if (name.startsWith(MEDIA_CB_) && name.endsWith(suffix)) {
				return name;
			}
		}
		return null;
	}

	/// Run a collect's continuation with `digits`, and clear what it was counting.
	private static void completeCollect(SipApplicationSession app, String key, String digits) {
		@SuppressWarnings("unchecked")
		Callback<SignalDetectorEvent> callback = (Callback<SignalDetectorEvent>) app.getAttribute(key);
		app.removeAttribute(key);
		app.removeAttribute(key + DIGITS_WANTED);
		app.removeAttribute(key + DIGITS_HEARD);
		if (callback != null) {
			callback.accept(new CollectedDigits(digits));
		}
	}

	/// The completion BLADE hands a collect that out-of-band digits finished. It has no source: the
	/// detector is a live object on whichever node armed it, and the digits came from SIP.
	private static final class CollectedDigits implements SignalDetectorEvent {
		private final String digits;

		CollectedDigits(String digits) {
			this.digits = digits;
		}

		@Override
		public String getSignalString() {
			return digits;
		}

		@Override
		public javax.media.mscontrol.Value[] getSignalBuffer() {
			return new javax.media.mscontrol.Value[0];
		}

		@Override
		public int getPatternIndex() {
			return -1;
		}

		@Override
		public javax.media.mscontrol.Qualifier getQualifier() {
			return SignalDetectorEvent.NUM_SIGNALS_DETECTED;
		}

		@Override
		public javax.media.mscontrol.resource.Trigger getRTCTrigger() {
			return null;
		}

		@Override
		public SignalDetector getSource() {
			return null;
		}

		@Override
		public javax.media.mscontrol.EventType getEventType() {
			return SignalDetectorEvent.RECEIVE_SIGNALS_COMPLETED;
		}

		@Override
		public boolean isSuccessful() {
			return true;
		}

		@Override
		public javax.media.mscontrol.MediaErr getError() {
			return MediaEvent.NO_ERROR;
		}

		@Override
		public String getErrorText() {
			return null;
		}
	}

	/// Extract the DTMF from a SIP INFO request and [#deliverDtmf] it to the call's SignalDetector. A
	/// media app routes in-dialog INFO here from its servlet's INFO callflow; the app still owns the
	/// INFO's SIP response. Returns true if a digit was delivered to an armed detector — false for
	/// non-DTMF INFO (e.g. a video keyframe request) or when nothing is collecting.
	public static boolean deliverInfoDtmf(SipServletRequest info) throws IOException {
		if (info == null) {
			return false;
		}
		String digits = parseDtmf(info.getContentType(), info.getContent());
		return digits != null && deliverDtmf(info.getApplicationSession(), digits);
	}

	/// Send a DTMF digit out of band, as a SIP `INFO` carrying `application/dtmf-relay`.
	///
	/// The send half of [#deliverInfoDtmf], and out of band for the same reason the receive half is:
	/// **there is no in-band alternative available.** JSR-309 nominally emits tones through
	/// `MediaGroup.getSignalGenerator()`, but no driver behind this framework implements it — the
	/// call throws — so a digit cannot be injected into the media stream at all. That makes INFO the
	/// only path, not merely the convenient one.
	///
	/// Being signaling-plane also means it works identically whether a call is anchored on a media
	/// server or relayed peer-to-peer. A relayed call has no media session to inject into and never
	/// will, since its media is encrypted end to end; the INFO rides the SIP dialog either way.
	///
	/// The body is the de-facto Cisco format the field actually speaks, and the one [#parseDtmf]
	/// reads back:
	///
	/// ```
	/// Signal=5
	/// Duration=250
	/// ```
	///
	/// Fire-and-forget: the response is ignored, because there is nothing useful to do about a digit
	/// the far end declined. `session` is the SIP session facing the party that should hear the tone.
	///
	/// @param session  the dialog to send on; must be confirmed
	/// @param digit    one of `0`-`9`, `*`, `#`, `A`-`D`
	/// @param duration tone duration in milliseconds, as advertised to the far end
	/// @return true if the INFO was sent
	public boolean sendInfoDtmf(SipSession session, String digit, int duration) {
		if (session == null || digit == null || digit.isEmpty() || !session.isValid()) {
			return false;
		}
		try {
			SipServletRequest info = session.createRequest(INFO);
			info.setContent(("Signal=" + digit + "\r\nDuration=" + duration + "\r\n")
					.getBytes(StandardCharsets.US_ASCII), "application/dtmf-relay");
			sendRequest(info, response -> {
				// Nothing to do either way. A far end that refuses INFO cannot be made to accept a
				// digit, and failing the call over one would be a worse answer than a missed tone.
			});
			return true;
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("MediaCallflow.sendInfoDtmf - could not send '" + digit + "': " + e);
			}
			return false;
		}
	}

	/// Parse the DTMF string from an INFO body. Handles the two carriages seen in the field:
	/// `application/dtmf-relay` (the de-facto Cisco format — `Signal=<d>` lines, with `10`/`11`
	/// sometimes standing in for `*`/`#`) and `application/dtmf` (the bare digit). Returns null if the
	/// content is neither. This is not an IETF-standardized format; it matches what gateways send.
	/// Public so a media app can reuse the parse without also delivering (e.g. to log or gate on a
	/// digit); [#deliverInfoDtmf] is the one-call parse-and-deliver path.
	public static String parseDtmf(String contentType, Object content) {
		if (contentType == null || content == null) {
			return null;
		}
		String ct = contentType.toLowerCase();
		String body = (content instanceof byte[])
				? new String((byte[]) content, StandardCharsets.US_ASCII)
				: content.toString();
		if (ct.startsWith("application/dtmf-relay")) {
			for (String line : body.split("\\r?\\n")) {
				int eq = line.indexOf('=');
				if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase("Signal")) {
					return normalizeSignal(line.substring(eq + 1).trim());
				}
			}
			return null;
		}
		if (ct.startsWith("application/dtmf")) {
			return normalizeSignal(body.trim());
		}
		return null;
	}

	/// Normalize one signaled DTMF token to its digit character. Passes through `0`-`9`, `*`, `#`,
	/// `A`-`D`; maps the numeric `10`/`11` encodings to `*`/`#`. Null/empty → null.
	private static String normalizeSignal(String s) {
		if (s == null || s.isEmpty()) {
			return null;
		}
		switch (s) {
		case "10":
			return "*";
		case "11":
			return "#";
		default:
			return s;
		}
	}

	// ================================================================ machinery

	/// Register `callback` as the continuation for `verb` on `notifier`'s session,
	/// and attach the framework dispatcher. Stores the callback on the replicated
	/// [SipApplicationSession] (so it survives failover) keyed by MediaSession URI +
	/// verb. Requires the MediaSession to have been bound ([#bindMediaSession]).
	private <E extends MediaEvent<?>> Armed arm(MediaEventNotifier<E> notifier, String verb, Callback<E> callback)
			throws MsControlException {
		MediaSession ms = notifier.getMediaSession();
		// The continuation is keyed by, and the app resolved from, the resource's container when the
		// app bound one (a shared conference session); otherwise by the MediaSession.
		javax.media.mscontrol.MediaObject container = containerOf(notifier);
		String appId = (container == null) ? null : boundAppId(container);
		String keyUri = (appId == null) ? ms.getURI().toString() : container.getURI().toString();
		if (appId == null) {
			appId = (String) ms.getAttribute(SIP_APP_SESSION_ID);
		}
		if (appId == null) {
			throw new MsControlException(
					"MediaCallflow: MediaSession is not bound to a SipApplicationSession "
							+ "(create it with createMediaSession(app) or call bindMediaSession)");
		}
		SipApplicationSession app = getSipUtil().getApplicationSessionById(appId);
		if (app == null) {
			throw new MsControlException("MediaCallflow: SipApplicationSession " + appId + " no longer valid");
		}
		app.setAttribute(cbKey(keyUri, verb), callback);
		notifier.addListener(new MediaDispatcher<E>(appId, keyUri, verb));
		// Hear media-server loss on every container a verb touches. The listener is equal per media
		// session, so a driver that keeps listeners as a set holds one per container and reports a
		// loss once per session.
		if (container instanceof AllocationEventNotifier) {
			String owner = (String) ms.getAttribute(SIP_APP_SESSION_ID);
			((AllocationEventNotifier) container)
					.addListener(new MediaLossListener(owner != null ? owner : appId, ms.getURI().toString()));
		}
		// Keep the failover recovery record current with whatever media coordinates now exist.
		captureRecovery(app, ms);
		return new Armed(app, cbKey(keyUri, verb));
	}

	/// Where [#arm] stored a continuation: the app session and the attribute key.
	private static final class Armed {
		final SipApplicationSession app;
		final String key;

		Armed(SipApplicationSession app, String key) {
			this.app = app;
			this.key = key;
		}
	}

	/// Sees whether a resource reported `expected` while an action ran. See [#pauseRecording].
	private static final class Confirmation implements MediaEventListener<RecorderEvent> {
		private final javax.media.mscontrol.EventType expected;
		volatile boolean seen;

		Confirmation(javax.media.mscontrol.EventType expected) {
			this.expected = expected;
		}

		@Override
		public void onEvent(RecorderEvent event) {
			if (event != null && expected.equals(event.getEventType())) {
				seen = true;
			}
		}
	}

	/// The resource container ([NetworkConnection] / [MediaGroup]) a notifier belongs to, or null
	/// when the notifier is not a contained resource.
	private static javax.media.mscontrol.MediaObject containerOf(MediaEventNotifier<?> notifier) {
		if (notifier instanceof javax.media.mscontrol.resource.Resource) {
			Object c = ((javax.media.mscontrol.resource.Resource<?>) notifier).getContainer();
			if (c instanceof javax.media.mscontrol.MediaObject) {
				return (javax.media.mscontrol.MediaObject) c;
			}
		}
		return null;
	}

	/// The app-session id stamped on a container by [#bindMediaObject], or null.
	private static String boundAppId(javax.media.mscontrol.MediaObject container) {
		Parameters p = container.getParameters(new javax.media.mscontrol.Parameter[] { SIP_APP_SESSION_ID_PARAMETER });
		Object v = (p == null) ? null : p.get(SIP_APP_SESSION_ID_PARAMETER);
		return (v == null) ? null : v.toString();
	}

	/// Resolve the [SipApplicationSession] that owns `ms` from the id stamped by [#bindMediaSession],
	/// or null if the session is unbound or no longer valid.
	private static SipApplicationSession appOf(MediaSession ms) {
		String appId = (String) ms.getAttribute(SIP_APP_SESSION_ID);
		return appId == null ? null : getSipUtil().getApplicationSessionById(appId);
	}

	/// Copy the recovery record the driver publishes on `ms` ([#RECOVERY_ATTRIBUTE]) onto the replicated
	/// `app`, so a node that takes over can [#reattach]. No-op when the driver publishes none.
	private static void captureRecovery(SipApplicationSession app, MediaSession ms) {
		if (app == null || ms == null) {
			return;
		}
		Object record = ms.getAttribute(RECOVERY_ATTRIBUTE);
		if (record != null) {
			app.setAttribute(MEDIA_REC_ + ms.getURI(), record.toString());
		}
	}

	// ================================================================= failover

	/// Rebuild the live media objects for `app` on the node that has taken over after a cluster
	/// failover, and return the reclaimed [MediaSession] (or null if there is nothing to recover or
	/// the driver does not support recovery). The continuations themselves rode the replicated SAS
	/// across the failover; this rebuilds the live driver objects that were lost with the old engine —
	/// e.g. so a teardown can [MediaSession#release] the still-running media instead of leaking it.
	///
	/// The app speaks only 309, so it calls this (typically from its BYE/failover path) without any
	/// knowledge of the underlying media server. The driver does the reclaim, from the record it
	/// published ([#RECOVERY_ATTRIBUTE]), through the standard [MsControlFactory#getMediaObject].
	public static MediaSession reattach(SipApplicationSession app) throws MsControlException {
		MsControlFactory factory = msControlFactory;
		if (app == null || factory == null) {
			return null;
		}
		MediaSession cached = LIVE.get(app.getId());
		if (cached != null) {
			return cached; // already reattached on this node — one socket per session, not one per ask
		}
		String msUri = findBoundMediaSessionUri(app);
		if (msUri == null) {
			return null;
		}
		Object record = app.getAttribute(MEDIA_REC_ + msUri);
		if (record == null) {
			return null; // the driver published nothing to recover from
		}
		javax.media.mscontrol.MediaObject found;
		try {
			found = factory.getMediaObject(URI.create(record.toString()));
		} catch (IllegalArgumentException e) {
			throw new MsControlException("the recovery record for " + msUri + " is not a URI", e);
		}
		if (!(found instanceof MediaSession)) {
			return null;
		}
		MediaSession ms = (MediaSession) found;
		bindMediaSession(ms, app); // re-stamp the binding on the rebuilt (fresh) live object
		LIVE.put(app.getId(), ms);
		return ms;
	}

	/// Node-local cache of media sessions rebuilt by [#reattach], keyed by app-session id. A
	/// reattach may be asked for twice — a refresh from the media server, then the call's next
	/// message — and must not open two control sockets for one session. Entries leave via
	/// [#forget] when the app releases the session.
	private static final java.util.concurrent.ConcurrentHashMap<String, MediaSession> LIVE = new java.util.concurrent.ConcurrentHashMap<>();

	/// The media session already reattached on this node for `appId`, or null.
	public static MediaSession liveSession(String appId) {
		return (appId == null) ? null : LIVE.get(appId);
	}

	/// Drop the reattached session for `appId` from the node-local cache — call after releasing it.
	public static void forget(String appId) {
		if (appId != null) {
			LIVE.remove(appId);
		}
	}

	/// Release the reattached session for `appId`, if there is one, and forget it. The app calls
	/// this when it tears a call down: after a refresh on the *same* node the app's own objects
	/// are stale (their socket died) and this is the live one — releasing only the stale copy
	/// would leave the media running under the reattached control session. No-op otherwise.
	public static void releaseReattached(String appId) {
		MediaSession ms = (appId == null) ? null : LIVE.remove(appId);
		if (ms != null) {
			try {
				ms.release();
			} catch (Exception ignore) {
				// best effort
			}
		}
	}

	/// Release every reattached session on this node — servlet destroy, so a redeploy leaves no
	/// control socket (and no media) behind.
	public static void releaseAllReattached() {
		for (String appId : new java.util.ArrayList<>(LIVE.keySet())) {
			releaseReattached(appId);
		}
	}

	// ============================================================ media-server loss

	/// What an application registers to hear that a call's **media server** went away under it —
	/// the engine's control socket died and it was not this side closing. Engine loss looks
	/// nothing like this (the media keeps running and another engine reattaches); media-server
	/// loss means the pipeline is gone, the phones are streaming at a dead address, and the only
	/// recovery is to re-anchor on another node and re-INVITE every leg. That re-anchoring is the
	/// listener's job — the framework only delivers the fact.
	public interface MediaLostListener {
		/// `appId` owns the lost media session `msUri`. Called on a dedicated thread, outside
		/// any SIP lock — take it ([com.bea.wcp.sip.WlssSipApplicationSession#doAction]) before
		/// touching call state.
		void mediaSessionLost(String appId, String msUri);
	}

	private static volatile MediaLostListener mediaLostListener;

	/// Register the application's media-server-loss handler (servlet init; clear at destroy).
	public static void setMediaLostListener(MediaLostListener listener) {
		mediaLostListener = listener;
	}

	/// Report a lost media session to the application, if it registered.
	private static void mediaSessionLost(String appId, String msUri) {
		MediaLostListener listener = mediaLostListener;
		if (listener != null && appId != null) {
			listener.mediaSessionLost(appId, msUri);
		}
	}

	/// Turns JSR-309's [AllocationEvent#IRRECOVERABLE_FAILURE] on a media session's containers into a
	/// [MediaLostListener] call. [#arm] puts one on every container a verb touches, and they compare
	/// equal per owner and media session, so a driver that keeps its listeners as a set reports a loss
	/// once per session. A driver that reports it once per container repeats it; the application's
	/// listener already has to tolerate a second report, because a loss can reach it from more than
	/// one leg.
	static final class MediaLossListener implements AllocationEventListener, Serializable {
		private static final long serialVersionUID = 1L;

		private final String appId;
		private final String msUri;

		MediaLossListener(String appId, String msUri) {
			this.appId = appId;
			this.msUri = msUri;
		}

		@Override
		public void onEvent(AllocationEvent event) {
			if (event != null && AllocationEvent.IRRECOVERABLE_FAILURE.equals(event.getEventType())) {
				mediaSessionLost(appId, msUri);
			}
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof MediaLossListener)) {
				return false;
			}
			MediaLossListener that = (MediaLossListener) other;
			return java.util.Objects.equals(appId, that.appId) && java.util.Objects.equals(msUri, that.msUri);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(appId, msUri);
		}
	}

	/// Convenience overload that resolves the app session by id first (for callers that hold only the
	/// id, e.g. a BYE handler keyed by app-session id). Returns null if the session is gone.
	public static MediaSession reattach(String appId) throws MsControlException {
		SipApplicationSession app = (appId == null) ? null : getSipUtil().getApplicationSessionById(appId);
		return reattach(app);
	}

	/// The first media-session URI bound to `app` (see the `MEDIA_MS_` markers written by
	/// [#bindMediaSession]), or null if none. A player call anchors exactly one media session.
	private static String findBoundMediaSessionUri(SipApplicationSession app) {
		java.util.Iterator<String> names = app.getAttributeNames();
		while (names.hasNext()) {
			String n = names.next();
			if (n.startsWith(MEDIA_MS_)) {
				return n.substring(MEDIA_MS_.length());
			}
		}
		return null;
	}

	/// The single framework [MediaEventListener] behind every media verb. Holds only
	/// serializable coordinates (app-session id, MediaSession URI, verb) — never the
	/// continuation itself, which lives in the replicated SAS. On an event it
	/// re-enters the app-session lock and runs the stashed continuation, so the
	/// continuation executes under the same lock a SIP continuation would.
	static final class MediaDispatcher<E extends MediaEvent<?>> implements MediaEventListener<E>, Serializable {
		private static final long serialVersionUID = 1L;

		private final String appId;
		private final String msUri;
		private final String verb;

		MediaDispatcher(String appId, String msUri, String verb) {
			this.appId = appId;
			this.msUri = msUri;
			this.verb = verb;
		}

		@Override
		public void onEvent(final E event) {
			if (isProgress(event)) {
				return; // a report on the way to finishing, not the completion the continuation waits for
			}
			final SipApplicationSession app = getSipUtil().getApplicationSessionById(appId);
			if (app == null) {
				return; // call is gone; nothing to continue
			}
			try {
				// Re-enter the app-session lock. If the driver already fired us under
				// the lock, doAction is a re-entrant no-op; otherwise it acquires it.
				((WlssSipApplicationSession) app).doAction(new WlssAction() {
					@Override
					public Object run() throws Exception {
						String key = cbKey(msUri, verb);
						@SuppressWarnings("unchecked")
						Callback<E> callback = (Callback<E>) app.getAttribute(key);
						if (callback != null) {
							app.removeAttribute(key); // one-shot, like a response callback
							app.removeAttribute(key + DIGITS_WANTED);
							app.removeAttribute(key + DIGITS_HEARD);
							callback.accept(event);   // Callback.accept wraps checked exceptions
						}
						return null;
					}
				});
			} catch (Exception e) {
				sipLogger.severe("MediaCallflow.MediaDispatcher: continuation for " + verb + " on " + msUri
						+ " failed: " + e.getMessage());
			}
		}
	}

	private static String cbKey(String mediaSessionUri, String verb) {
		return MEDIA_CB_ + mediaSessionUri + ":" + verb;
	}

	/// Whether `event` is a report a resource makes on the way to finishing: a recorder that started,
	/// paused or resumed, a detector that heard one signal. A continuation waits for the completion,
	/// so these pass it by. Without this a pause would run a recording's continuation, and one
	/// transcribed utterance would complete a pending DTMF collect.
	static boolean isProgress(MediaEvent<?> event) {
		javax.media.mscontrol.EventType type = (event == null) ? null : event.getEventType();
		return RecorderEvent.STARTED.equals(type) || RecorderEvent.PAUSED.equals(type)
				|| RecorderEvent.RESUMED.equals(type) || SignalDetectorEvent.SIGNAL_DETECTED.equals(type);
	}
}
