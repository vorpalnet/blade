package org.vorpal.blade.services.webrtc;

import javax.servlet.sip.SipApplicationSession;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;

import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.CloudEvent;

/// Carries an event from the browser into the callflow that is waiting for it.
///
/// This is the browser-side twin of [org.vorpal.blade.framework.Callflow#expectRequest]. A callflow
/// says "when the browser answers, do this", and that continuation waits on the replicated
/// [SipApplicationSession] until the event arrives — which may be on a different node, on a
/// WebSocket thread, minutes later, after a failover.
///
/// Two properties make that safe, and both are borrowed rather than invented:
///
/// - **The continuation is stored, not held.** A [Callback] is [java.io.Serializable], so the
///   container replicates it with the rest of the session. The waiting callflow occupies no thread.
/// - **Delivery re-enters the session lock.** The event surfaces on a WebSocket thread, outside any
///   SIP lock, so [#deliver] re-acquires it through `WlssSipApplicationSession.doAction` before
///   running the continuation. Call state is therefore mutated under the same lock a SIP
///   continuation would hold — the same discipline
///   [org.vorpal.blade.framework.v3.media.MediaCallflow] uses for JSR-309 media events.
public final class BrowserSignals {

	/// Prefix for the [SipApplicationSession] attribute holding a continuation. The full key is
	/// `BROWSER_CB_ + <event type>`, so a callflow can wait on several browser events at once.
	private static final String BROWSER_CB_ = "org.vorpal.blade.webrtc.cb.";

	/// Suffix marking a continuation that stays armed after firing (see [#expectRepeating]).
	private static final String REPEATING = ".repeating";

	/// [SipApplicationSession] attribute (String): the address of the browser on this call, so the
	/// SIP side knows who to send events to without re-deriving it.
	public static final String BROWSER_AOR = "org.vorpal.blade.webrtc.aor";

	private BrowserSignals() {
	}

	/// Wait for one `eventType` from the browser, then run `continuation`.
	///
	/// One-shot: the continuation is removed before it runs, so a re-entrant event cannot fire it
	/// twice. Arming the same event type again replaces the previous continuation.
	public static void expect(SipApplicationSession app, String eventType, Callback<CloudEvent> continuation) {
		app.setAttribute(BROWSER_CB_ + eventType, continuation);
	}

	/// Wait for **every** `eventType` from the browser, not just the next one.
	///
	/// For events that recur across a call — trickled ICE candidates, DTMF digits, a record button
	/// toggled on and off — where re-arming after each one would be noise and would leave a window
	/// in which an event is dropped.
	public static void expectRepeating(SipApplicationSession app, String eventType,
			Callback<CloudEvent> continuation) {
		app.setAttribute(BROWSER_CB_ + eventType, continuation);
		app.setAttribute(BROWSER_CB_ + eventType + REPEATING, Boolean.TRUE);
	}

	/// Stop waiting for `eventType`.
	public static void cancel(SipApplicationSession app, String eventType) {
		app.removeAttribute(BROWSER_CB_ + eventType);
		app.removeAttribute(BROWSER_CB_ + eventType + REPEATING);
	}

	/// Run `body` under the application-session lock, re-entering it exactly the way [#deliver] does.
	///
	/// For work that starts on a WebSocket thread and touches replicated call state. There is one
	/// such place: a RELAY-mode outbound call is placed straight from the thread that carried
	/// `call.offer`, and nothing on that path defers to a continuation, so the INVITE is on the wire
	/// while this thread is still working — a fast `100` or `180` can be inside a SIP continuation
	/// touching the same session concurrently. Everywhere else in this application already runs
	/// either on a SIP container thread or inside a continuation that took the lock first.
	public static void underLock(final SipApplicationSession app, final Runnable body) {
		if (app instanceof WlssSipApplicationSession) {
			try {
				((WlssSipApplicationSession) app).doAction(new WlssAction() {
					@Override
					public Object run() {
						body.run();
						return null;
					}
				});
			} catch (Exception e) {
				if (Callflow.getSipLogger() != null) {
					Callflow.getSipLogger().severe("BrowserSignals.underLock failed: " + e);
				}
			}
		} else {
			body.run(); // no container lock (tests / non-Wlss sessions)
		}
	}

	/// Run whatever continuation is waiting on `event`'s type, under the application-session lock.
	/// A no-op when nothing is waiting — a browser may send a hangup for a call the network already
	/// tore down, and that race is normal rather than exceptional.
	public static void deliver(final SipApplicationSession app, final CloudEvent event) {
		final String key = BROWSER_CB_ + event.getType();
		try {
			if (app instanceof WlssSipApplicationSession) {
				((WlssSipApplicationSession) app).doAction(new WlssAction() {
					@Override
					public Object run() {
						invokeStored(app, key, event);
						return null;
					}
				});
			} else {
				invokeStored(app, key, event); // no container lock (tests / non-Wlss sessions)
			}
		} catch (Exception e) {
			if (Callflow.getSipLogger() != null) {
				Callflow.getSipLogger().severe("BrowserSignals.deliver: " + key + " failed: " + e);
			}
		}
	}

	private static void invokeStored(SipApplicationSession app, String key, CloudEvent event) {
		@SuppressWarnings("unchecked")
		Callback<CloudEvent> continuation = (Callback<CloudEvent>) app.getAttribute(key);
		if (continuation == null) {
			return;
		}
		// One-shot by default: remove before invoking, so a continuation that arms the next step
		// cannot be re-entered by its own event. Repeating continuations stay armed.
		if (!Boolean.TRUE.equals(app.getAttribute(key + REPEATING))) {
			app.removeAttribute(key);
		}
		try {
			continuation.accept(event);
		} catch (Exception e) {
			if (Callflow.getSipLogger() != null) {
				Callflow.getSipLogger().severe("BrowserSignals: continuation " + key + " threw: " + e);
			}
		}
	}
}
