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

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;

import org.vorpal.blade.framework.v2.callflow.Callback;
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
/// must acquire it). The Gryphon Kurento driver — which we control — will fire
/// events under the lock; this defensive `doAction` makes the API correct for
/// arbitrary drivers too. **Failover re-attach** (re-resolving live media objects
/// by URI and re-registering dispatchers on the node that takes over) is not yet
/// implemented here — the continuations survive in the replicated SAS, but the
/// live media objects/listeners must be rebuilt on failover; see [#TODO_reattach].
public abstract class MediaCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	/// [MediaSession] attribute (String): the id of the owning [SipApplicationSession].
	/// Stamped by [#bindMediaSession]; read by [MediaDispatcher] to recover the app
	/// session from any media event.
	public static final String SIP_APP_SESSION_ID = "org.vorpal.blade.v3.media.sasId";

	/// Prefix for the [SipApplicationSession] attribute under which a verb stashes
	/// its continuation. Full key is `MEDIA_CB_ + <mediaSessionUri> + ":" + <verb>`.
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
		app.setAttribute(MEDIA_MS_ + ms.getURI(), ms.getURI().toString());
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
	protected void record(MediaGroup mediaGroup, URI destination, Callback<RecorderEvent> onComplete)
			throws MsControlException {
		Recorder recorder = mediaGroup.getRecorder();
		arm(recorder, RECORD, onComplete);
		recorder.record(destination, null, Parameters.NO_PARAMETER);
	}

	/// Feed the caller's SDP offer to `networkConnection` and run `onAnswer` when
	/// the media server's answer is ready. The answer bytes are on the event:
	/// [SdpPortManagerEvent#getMediaServerSdp] — hand them straight to your
	/// `200 OK`. This is the 309 side of the 3PCC anchor.
	protected void offer(NetworkConnection networkConnection, byte[] callerSdpOffer,
			Callback<SdpPortManagerEvent> onAnswer) throws MsControlException {
		SdpPortManager sdp = networkConnection.getSdpPortManager();
		arm(sdp, SDP, onAnswer);
		sdp.processSdpOffer(callerSdpOffer);
		// The anchor now exists on the media server (processSdpOffer created the pipeline + leg).
		// Refresh the failover recovery record so a node that takes over between now and the next
		// verb can still reclaim — and release — the running media. Closes the leak window.
		captureRecovery(appOf(networkConnection.getMediaSession()), networkConnection.getMediaSession());
	}

	/// Join two media endpoints (e.g. a caller's [NetworkConnection] to a conference
	/// [javax.media.mscontrol.mixer.MediaMixer]). Synchronous per the 309
	/// [Joinable#join] contract — it returns when the join is established. The
	/// asynchronous [Joinable#joinInitiate] variant (with a `JoinEvent` continuation)
	/// is a future addition; the synchronous form matches how the reference
	/// conference sample joins legs.
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
		String appId = (String) ms.getAttribute(SIP_APP_SESSION_ID);
		if (appId == null) {
			throw new MsControlException(
					"MediaCallflow: MediaSession is not bound to a SipApplicationSession "
							+ "(create it with createMediaSession(app) or call bindMediaSession)");
		}
		SipApplicationSession app = getSipUtil().getApplicationSessionById(appId);
		if (app == null) {
			throw new MsControlException("MediaCallflow: SipApplicationSession " + appId + " no longer valid");
		}
		app.setAttribute(cbKey(ms.getURI().toString(), verb), callback);
		notifier.addListener(new MediaDispatcher<E>(appId, ms.getURI().toString(), verb));
		// Keep the failover recovery record current with whatever media coordinates now exist.
		captureRecovery(app, ms);
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
		String msUri = findBoundMediaSessionUri(app);
		if (msUri == null) {
			return null;
		}
		MediaSession ms = ((MediaSessionRecovery) factory).rebuild(app, msUri);
		if (ms != null) {
			bindMediaSession(ms, app); // re-stamp the binding on the rebuilt (fresh) live object
		}
		return ms;
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
