package org.vorpal.blade.framework.v3.media;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
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
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.media.mscontrol.networkconnection.SdpPortManager;
import javax.media.mscontrol.networkconnection.SdpPortManagerEvent;
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
/// its own recovery record through [MediaSessionRecovery].
public abstract class MediaCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	/// [MediaSession] attribute (String): the id of the owning [SipApplicationSession].
	/// Stamped by [#bindMediaSession]; read by [MediaDispatcher] to recover the app
	/// session from any media event.
	public static final String SIP_APP_SESSION_ID = "org.vorpal.blade.v3.media.sasId";

	/// [MediaSession] attribute (String): the name of the application (the deployment /
	/// context-root name) that owns the session. Stamped by [#bindMediaSession] from
	/// [#setApplicationName]; a driver copies it onto the media server's objects so a party that
	/// finds them later — the media server itself, when a control socket dies — knows which
	/// application to call back ([MediaRefresh]).
	public static final String SIP_APP_NAME = "org.vorpal.blade.v3.media.app";

	/// The owning application's name, set once at servlet init (the base servlet does it).
	private static volatile String applicationName;

	public static void setApplicationName(String name) {
		applicationName = name;
	}

	public static String getApplicationName() {
		return applicationName;
	}

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
	/// The digits may reach the detector from the media plane (RFC 4733 / in-band tones the driver
	/// decodes) or from the signaling plane — a SIP INFO `application/dtmf-relay` body the app routes in
	/// via [#deliverDtmf] / [#deliverInfoDtmf]. Either source completes the same continuation; the app
	/// writes one `prompt` regardless of how the caller's phone carries DTMF.
	protected void prompt(MediaGroup mediaGroup, int numDigits, Callback<SignalDetectorEvent> onDigits)
			throws MsControlException {
		SignalDetector detector = mediaGroup.getSignalDetector();
		arm(detector, COLLECT, onDigits);
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
		Recorder recorder = mediaGroup.getRecorder();
		arm(recorder, RECORD, onComplete);
		recorder.record(resolveDestination(destination), null, Parameters.NO_PARAMETER);
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
	/// `app`'s armed [SignalDetector], completing a pending [#prompt]. Vendor-neutral: it resolves the
	/// call's media session and hands the digits to the driver's [DtmfSink]. Returns true if a detector
	/// was collecting and consumed them; false (no-op) if the driver has no `DtmfSink`, the app has no
	/// bound media session, or nothing is collecting. `digits` is the raw DTMF string, e.g. `"5"` or
	/// `"1234"`.
	public static boolean deliverDtmf(SipApplicationSession app, String digits) {
		MsControlFactory factory = msControlFactory;
		if (app == null || digits == null || digits.isEmpty() || !(factory instanceof DtmfSink)) {
			return false;
		}
		String msUri = findBoundMediaSessionUri(app);
		return msUri != null && ((DtmfSink) factory).deliverDtmf(msUri, digits);
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
	private <E extends MediaEvent<?>> void arm(MediaEventNotifier<E> notifier, String verb, Callback<E> callback)
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
		// Keep the failover recovery record current with whatever media coordinates now exist.
		captureRecovery(app, ms);
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

	/// If the installed factory supports failover recovery ([MediaSessionRecovery]), let it persist
	/// `ms`'s current recovery state into the replicated `app`. No-op for factories that don't.
	private static void captureRecovery(SipApplicationSession app, MediaSession ms) {
		MsControlFactory factory = msControlFactory;
		if (app != null && factory instanceof MediaSessionRecovery) {
			((MediaSessionRecovery) factory).captureInto(app, ms);
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
	/// knowledge of the underlying media server; the driver ([MediaSessionRecovery]) does the reclaim.
	public static MediaSession reattach(SipApplicationSession app) throws MsControlException {
		MsControlFactory factory = msControlFactory;
		if (app == null || !(factory instanceof MediaSessionRecovery)) {
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
		MediaSession ms = ((MediaSessionRecovery) factory).rebuild(app, msUri);
		if (ms != null) {
			bindMediaSession(ms, app); // re-stamp the binding on the rebuilt (fresh) live object
			LIVE.put(app.getId(), ms);
		}
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

	/// Driver entry point: report a lost media session to the application, if it registered.
	public static void mediaSessionLost(String appId, String msUri) {
		MediaLostListener listener = mediaLostListener;
		if (listener != null && appId != null) {
			listener.mediaSessionLost(appId, msUri);
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
}
