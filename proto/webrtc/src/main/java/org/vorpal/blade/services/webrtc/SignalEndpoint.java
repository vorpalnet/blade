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
			case SignalProtocol.CALL_ACCEPT:
			case SignalProtocol.CALL_HANGUP:
			case SignalProtocol.CALL_DTMF:
			case SignalProtocol.ICE_CANDIDATE:
			case SignalProtocol.CALL_RECORD:
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
	}

	@OnError
	public void onError(Session session, Throwable t) {
		Logger logger = log();
		if (logger != null) {
			logger.warning("webrtc: socket error on " + session.getId() + ": " + t);
		}
		BrowserRegistry.unregister(session);
	}

	// ---- handlers -----------------------------------------------------------------------------

	/// Claim an address so calls can be routed to this browser.
	private void connect(Session session, CloudEvent event) {
		String aor = SignalProtocol.field(event, "aor");
		if (aor == null) {
			send(session, SignalProtocol.reason(SignalProtocol.ERROR, null, "session.connect requires an aor"));
			return;
		}
		BrowserRegistry.register(aor, session);
		Logger logger = log();
		if (logger != null) {
			logger.info("webrtc: " + aor + " registered on this node");
		}
		send(session, SignalProtocol.event(SignalProtocol.SESSION_READY, null,
				SignalProtocol.data().put("aor", aor)));
	}

	/// Originate a call on the browser's behalf.
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
		// Which callflow depends on what is on the other end. A registered browser can be reached
		// peer-to-peer with no media server at all; anything else is a phone, and a phone cannot do
		// ICE or DTLS-SRTP, so the media server is not optional.
		String target = SignalProtocol.field(event, "target");
		boolean targetIsBrowser = target != null && BrowserRegistry.isLocal(target);

		String callId = targetIsBrowser
				? new BrowserToBrowser().start(aor, target, event)
				: new OutboundFromBrowser().start(aor, event);

		if (callId != null) {
			fine("webrtc: " + aor + " placed call " + callId
					+ (targetIsBrowser ? " (relayed, no media server)" : " (anchored)"));
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

		// Stamp the sender: a relayed call has two browsers on one session and has to know which
		// way to forward.
		if (event.getData() != null && event.getData().isObject()) {
			((com.fasterxml.jackson.databind.node.ObjectNode) event.getData())
					.put("from", BrowserRegistry.addressOf(session));
		}
		BrowserSignals.deliver(app, event);
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
