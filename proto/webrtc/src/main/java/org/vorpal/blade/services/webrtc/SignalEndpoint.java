package org.vorpal.blade.services.webrtc;

import java.io.IOException;

import javax.servlet.sip.SipApplicationSession;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.CloudEvent;

/// The browser's end of the gateway: one WebSocket per browser, carrying
/// [SignalProtocol] events.
///
/// A converged application may declare both an HTTP/WebSocket endpoint and a SIP servlet in one
/// WAR; WebLogic runs both containers over the same classloader. Nothing here is mapped in
/// `web.xml` — the annotation is the declaration.
///
/// ## Threading
///
/// Everything below runs on a container WebSocket thread, never under a SIP application-session
/// lock. Any work that touches replicated call state therefore has to re-enter that lock, which is
/// what [SipApplicationSession] resolution plus the callflow continuation is for. Writing a frame
/// back to a browser needs no lock at all, which is why [BrowserRegistry#deliver] can be called
/// from anywhere.
@ServerEndpoint(value = "/signal", subprotocols = { SignalProtocol.SUBPROTOCOL })
public class SignalEndpoint {

	/// Shared across sockets so the JWKS it caches is fetched once per node
	/// rather than once per browser. The container creates a new endpoint
	/// instance per connection, which is why this is static.
	private static final BrowserAuthenticator AUTHENTICATOR = new BrowserAuthenticator();

	/// The framework logger, installed by [org.vorpal.blade.framework.AsyncSipServlet] at init. The
	/// WebSocket container can start before the SIP servlet does, so this is null for a short
	/// window at deployment.
	private static Logger log() {
		return Callflow.getSipLogger();
	}

	private static void fine(String message) {
		Logger logger = log();
		if (logger != null) {
			logger.fine(message);
		}
	}

	@OnOpen
	public void onOpen(Session session) {
		// Hold the socket open but anonymous until session.connect names an address. Registering
		// on open would mean an unauthenticated socket could receive somebody else's calls.
		session.setMaxIdleTimeout(0);
		fine("webrtc: socket opened, " + session.getId());
	}

	@OnMessage
	public void onMessage(String json, Session session) {
		CloudEvent event;
		try {
			event = CloudEvent.fromJson(json);
		} catch (IOException e) {
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, null, "malformed event"));
			return;
		}

		String type = event.getType();
		if (type == null) {
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, null, "missing event type"));
			return;
		}

		try {
			switch (type) {
			case SignalProtocol.SESSION_CONNECT:
				connect(session, event);
				break;
			case SignalProtocol.CALL_ANSWER:
			case SignalProtocol.CALL_HANGUP:
			case SignalProtocol.CALL_DTMF:
			case SignalProtocol.ICE_CANDIDATE:
				forwardToCall(session, event);
				break;
			case SignalProtocol.CALL_OFFER:
				placeCall(session, event);
				break;
			default:
				send(session, SignalProtocol.reason(SignalProtocol.ERROR, event.getSubject(),
						"unsupported event type: " + type));
				break;
			}
		} catch (Exception e) {
			Logger logger = log();
			if (logger != null) {
				logger.severe("webrtc: failed handling " + type + ": " + e);
			}
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, event.getSubject(), "internal error"));
		}
	}

	@OnClose
	public void onClose(Session session, CloseReason reason) {
		String aor = BrowserRegistry.unregister(session);
		fine("webrtc: socket closed, " + session.getId() + (aor != null ? " (" + aor + ")" : "")
				+ " reason=" + reason.getCloseCode());
		deregisterQuietly(aor);
	}

	@OnError
	public void onError(Session session, Throwable t) {
		Logger logger = log();
		if (logger != null) {
			logger.warning("webrtc: socket error on " + session.getId() + ": " + t);
		}
		deregisterQuietly(BrowserRegistry.unregister(session));
	}

	// ---- handlers -----------------------------------------------------------------------------

	/// Claim an address so calls can be routed to this browser.
	///
	/// This is the gate. Everything else the browser can ask for — placing a
	/// call, answering one, sending DTMF — first checks that the socket is
	/// registered, so refusing here refuses all of it. The address that gets
	/// bound is the one the token grants, never the one the browser typed; see
	/// [BrowserAuthenticator].
	private void connect(Session session, CloudEvent event) {
		String requestedAor = SignalProtocol.field(event, "aor");
		String token = SignalProtocol.field(event, "token");

		BrowserAuthenticator.Decision decision =
				AUTHENTICATOR.authorize(WebrtcServlet.jwtConfig(), token, requestedAor);

		Logger logger = log();
		if (!decision.isAllowed()) {
			if (logger != null) {
				logger.warning("webrtc: refused " + session.getId()
						+ (requestedAor != null ? " claiming " + requestedAor : "")
						+ ": " + decision.getReason());
			}
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, null, decision.getReason()));
			// Say why, then hang up. onOpen disables the idle timeout so a
			// registered browser's socket survives a quiet call; without closing
			// here, a socket that was refused would enjoy the same courtesy and
			// sit open indefinitely having proved nothing.
			closeQuietly(session);
			return;
		}

		// One canonical form end-to-end: the location service lowercases its
		// keys (getAccountName), BrowserRegistry compares exactly. Lowercasing
		// once here is what keeps the two stores naming the same browser.
		String aor = decision.getAor().toLowerCase();
		BrowserRegistry.register(aor, session);
		if (logger != null) {
			logger.info("webrtc: " + aor + " registered on this node"
					+ (decision.isAuthenticated()
							? " as '" + decision.getUser() + "'"
							: " WITHOUT authentication (browser authentication is disabled)"));
		}
		// `authenticated` tells the page whether the address it just claimed was
		// proved or merely accepted, so an open gateway is visible in the UI
		// rather than only in a log nobody is reading.
		send(session, SignalProtocol.event(SignalProtocol.SESSION_READY, null,
				SignalProtocol.data()
						.put("aor", aor)
						.put("authenticated", decision.isAuthenticated())));

		// Announce the browser to the SIP location service — after ready, and
		// best-effort: a browser the registrar cannot reach still relays
		// browser-to-browser, so a failure here degrades to exactly today's
		// behavior and must never cost the socket its session.
		try {
			new BrowserRegistration().register(aor);
		} catch (Exception e) {
			if (logger != null) {
				logger.warning("webrtc: could not send REGISTER for " + aor + ": " + e);
			}
		}
	}

	/// Withdraw `aor` from the location service, if it was ours to withdraw.
	/// Null means this socket had no binding or was already superseded — in the
	/// superseded case the replacement owns the registration now, and a
	/// deregister from the dying socket would tear down the live browser's.
	private void deregisterQuietly(String aor) {
		if (aor == null) {
			return;
		}
		try {
			new BrowserRegistration().deregister(aor);
		} catch (Exception e) {
			// Expiry is the backstop: the binding ages out at registerExpiresSeconds.
			fine("webrtc: could not send deregister for " + aor + ": " + e);
		}
	}

	/// Originate a call on the browser's behalf.
	///
	/// Every call goes out as a SIP INVITE — there is no WebSocket-only path, even when the target
	/// is a browser on this same node. Signaling always rides the SIP fabric, where the App Router,
	/// the location service and analytics can see it; what varies per call is only the media, which
	/// [OutboundFromBrowser] resolves via [MediaMode] (a browser-to-browser call still runs its
	/// media peer-to-peer — the SDP passes through the INVITE untouched).
	///
	/// The callflow runs on this WebSocket thread only until the INVITE is sent; everything after
	/// that is a continuation on a SIP thread under the application-session lock.
	private void placeCall(Session session, CloudEvent event) throws Exception {
		String aor = BrowserRegistry.addressOf(session);
		if (aor == null) {
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, event.getSubject(),
					"session.connect required first"));
			return;
		}
		String callId = new OutboundFromBrowser().start(aor, event);
		if (callId != null) {
			fine("webrtc: " + aor + " placed call " + callId);
		}
	}

	/// Hand an in-call event to the callflow that owns it.
	///
	/// The CloudEvents `subject` is the call id, which is also the application-session key the
	/// callflow indexed itself under — so a WebSocket thread on any node can find the call, even
	/// when the SIP side of it is being serviced elsewhere in the cluster.
	private void forwardToCall(Session session, CloudEvent event) throws IOException {
		String callId = event.getSubject();
		if (callId == null) {
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, null, "event requires a call subject"));
			return;
		}
		if (BrowserRegistry.addressOf(session) == null) {
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, callId, "session.connect required first"));
			return;
		}

		// By ID, not by key. getApplicationSessionByKey only resolves sessions minted with
		// SipFactory.createApplicationSessionByKey, and addIndexKey's partner is
		// getSipApplicationSessionIds(indexKey) — mixing them silently finds nothing, which the
		// browser then sees as "no such call". The callflows hand the browser the session id itself.
		SipApplicationSession app = Callflow.getSipUtil().getApplicationSessionById(callId);
		if (app == null || !app.isValid()) {
			// The call is already gone. Tell the browser so its UI can settle, rather than leaving
			// it showing a call that no longer exists anywhere.
			send(session, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId, "no such call"));
			return;
		}

		// Stamp the sender, so a continuation never has to trust an event's own claim about who
		// sent it — the socket it arrived on is the authority.
		if (event.getData() != null && event.getData().isObject()) {
			((com.fasterxml.jackson.databind.node.ObjectNode) event.getData())
					.put("from", BrowserRegistry.addressOf(session));
		}
		BrowserSignals.deliver(app, event);
	}

	private void closeQuietly(Session session) {
		try {
			session.close();
		} catch (IOException e) {
			fine("webrtc: could not close " + session.getId() + ": " + e);
		}
	}

	private void send(Session session, CloudEvent event) {
		try {
			String json = event.toJson();
			synchronized (session) {
				session.getBasicRemote().sendText(json);
			}
		} catch (IOException e) {
			fine("webrtc: could not write to " + session.getId() + ": " + e);
		}
	}
}
