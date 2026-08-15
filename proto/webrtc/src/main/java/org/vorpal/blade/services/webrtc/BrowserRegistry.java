package org.vorpal.blade.services.webrtc;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.Session;

import org.vorpal.blade.framework.v3.events.CloudEvent;

/// Which browsers are connected **to this node**, and how to write to them — the socket table,
/// not a registrar. The SIP location service for browsers is `proxy-registrar`, which
/// [BrowserRegistration] feeds on their behalf; this class answers only "do I hold the live
/// socket for this address, and how do I write a frame to it".
///
/// A [javax.websocket.Session] is a live socket: it cannot be serialized, cannot be replicated, and
/// is meaningless on any other engine. So this table is deliberately node-local and deliberately
/// static — the same shape `services/transfer` uses for the `AsyncResponse` it cannot serialize
/// either.
///
/// Node-local is not a cluster limitation here, because nothing ever has to reach this table from
/// another engine. [BrowserRegistration] registers a contact naming **this** engine, so the
/// registrar's fork — and with it the whole call, its dialog and its media — is routed to the node
/// holding the socket before it arrives. The socket table is node-local; the contact is what routes.
///
/// [#deliver] therefore answers "is this browser mine?", and a `false` means the binding is stale:
/// the socket has gone and the browser has not yet re-registered from wherever it reconnected.
/// Until it does, it is unreachable from every engine, not just this one.
public final class BrowserRegistry {

	/// Address-of-record -> the browser's socket on this node.
	private static final Map<String, Session> BY_AOR = new ConcurrentHashMap<>();

	/// WebSocket session id -> address-of-record, so a closing socket can be unregistered without
	/// the browser having to tell us who it was.
	private static final Map<String, String> AOR_BY_SESSION = new ConcurrentHashMap<>();

	private BrowserRegistry() {
	}

	/// Bind `aor` to `session` on this node. A second registration for the same address replaces
	/// the first and closes the old socket — reloading the page must not leave a ghost that
	/// silently swallows incoming calls.
	public static void register(String aor, Session session) {
		AOR_BY_SESSION.put(session.getId(), aor);
		Session previous = BY_AOR.put(aor, session);
		if (previous != null && !previous.getId().equals(session.getId())) {
			AOR_BY_SESSION.remove(previous.getId());
			closeQuietly(previous);
		}
	}

	/// Forget the browser behind `session`, if it is still the one bound. Returns the address that
	/// was released, or null if this socket was already superseded.
	public static String unregister(Session session) {
		String aor = AOR_BY_SESSION.remove(session.getId());
		if (aor == null) {
			return null;
		}
		// Only remove the binding if this socket still owns it; a reconnect may have replaced it.
		BY_AOR.remove(aor, session);
		return aor;
	}

	/// The address bound to `session`, or null.
	public static String addressOf(Session session) {
		return AOR_BY_SESSION.get(session.getId());
	}

	/// True when `aor` has a live socket on this node.
	public static boolean isLocal(String aor) {
		Session session = BY_AOR.get(aor);
		return session != null && session.isOpen();
	}

	/// Every address currently connected to this node.
	public static Set<String> localAddresses() {
		return BY_AOR.keySet();
	}

	/// Write `event` to `aor` if that browser is connected here.
	///
	/// @return true when the event was written; false when this node does not hold the socket —
	///         a stale binding, since a call for this browser would otherwise have been routed to
	///         the node that does hold it.
	public static boolean deliver(String aor, CloudEvent event) {
		Session session = BY_AOR.get(aor);
		if (session == null || !session.isOpen()) {
			return false;
		}
		try {
			String json = event.toJson();
			// One socket, potentially several call legs writing to it: serialize the sends, since
			// the WebSocket API forbids overlapping partial writes on one connection.
			synchronized (session) {
				session.getBasicRemote().sendText(json);
			}
			return true;
		} catch (IOException e) {
			// The browser vanished between the liveness check and the write. Drop the binding so
			// the next inbound call fails over instead of writing into a dead socket.
			unregister(session);
			closeQuietly(session);
			return false;
		}
	}

	private static void closeQuietly(Session session) {
		try {
			session.close();
		} catch (IOException ignore) {
			// best effort
		}
	}
}
