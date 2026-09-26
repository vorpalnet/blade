package org.vorpal.blade.services.webrtc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.Session;

import org.vorpal.blade.framework.v3.events.CloudEvent;

/// Which browsers are connected **to this node**, and how to write to them — the socket table,
/// not a registrar. The SIP location service for browsers is `proxy-registrar`, which
/// [BrowserRegistration] feeds on their behalf; this class answers only "do I hold a live
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
/// ## One account, several devices
///
/// An address may have several sockets at once: the same person on a laptop and a phone, or in two
/// windows. Each call belongs to one of them. A call the browser places is bound to the socket that
/// placed it before any event for it is written ([#bind]); an incoming call rings every socket of
/// the address and is bound to the first that answers. [#deliver] writes a call's events to the
/// socket it is bound to, and an unbound call's (a ring, a cancel) to every socket of the address.
/// Nothing else crosses devices: a page never sees another device's call.
public final class BrowserRegistry {

	/// Address-of-record -> the address's sockets on this node.
	private static final Map<String, Set<Session>> BY_AOR = new ConcurrentHashMap<>();

	/// WebSocket session id -> address-of-record, so a closing socket can be unregistered without
	/// the browser having to tell us who it was.
	private static final Map<String, String> AOR_BY_SESSION = new ConcurrentHashMap<>();

	/// Call id -> the socket holding the call.
	private static final Map<String, Session> BY_CALL = new ConcurrentHashMap<>();

	private BrowserRegistry() {
	}

	/// Add `session` to the sockets of `aor` on this node. Another socket for the address stays: a
	/// second device joins, it does not replace the first. A reloaded page's old socket closes on
	/// its own, and one that dies silently is found by [#pingAll].
	public static void register(String aor, Session session) {
		AOR_BY_SESSION.put(session.getId(), aor);
		BY_AOR.computeIfAbsent(aor, k -> ConcurrentHashMap.newKeySet()).add(session);
	}

	/// Forget the socket `session` and the calls bound to it.
	///
	/// @return the address when this was its last socket on this node, so the caller withdraws it
	///         from the location service; null while another device still holds the address, or
	///         when the socket was never registered
	public static String unregister(Session session) {
		String aor = AOR_BY_SESSION.remove(session.getId());
		BY_CALL.values().removeIf(held -> held.getId().equals(session.getId()));
		if (aor == null) {
			return null;
		}
		Set<Session> sockets = BY_AOR.computeIfPresent(aor, (k, set) -> {
			set.removeIf(s -> s.getId().equals(session.getId()));
			return set.isEmpty() ? null : set;
		});
		return (sockets == null) ? aor : null;
	}

	/// The address bound to `session`, or null.
	public static String addressOf(Session session) {
		return AOR_BY_SESSION.get(session.getId());
	}

	/// True when `aor` has a live socket on this node.
	public static boolean isLocal(String aor) {
		return !openSocketsOf(aor).isEmpty();
	}

	/// Every address currently connected to this node.
	public static Set<String> localAddresses() {
		return BY_AOR.keySet();
	}

	/// Bind the call `callId` to the socket `session`: from now on its events go to that socket
	/// alone.
	///
	/// @return false when another socket already holds the call (a second device answering a call
	///         the first has taken)
	public static boolean bind(String callId, Session session) {
		Session held = BY_CALL.putIfAbsent(callId, session);
		return held == null || held.getId().equals(session.getId());
	}

	/// The socket holding `callId`, or null while the call is unbound.
	public static Session holderOf(String callId) {
		return (callId == null) ? null : BY_CALL.get(callId);
	}

	/// The calls bound to `session`.
	public static List<String> callsOf(Session session) {
		List<String> calls = new ArrayList<>();
		BY_CALL.forEach((callId, held) -> {
			if (held.getId().equals(session.getId())) {
				calls.add(callId);
			}
		});
		return calls;
	}

	/// The call `callId` ended.
	public static void forgetCall(String callId) {
		if (callId != null) {
			BY_CALL.remove(callId);
		}
	}

	/// Write `event` to `aor`'s socket holding the call it names, or to every socket of `aor` while
	/// the call is unbound.
	///
	/// @return true when the event was written to at least one socket; false when this node holds
	///         none — a stale binding, since a call for this browser would otherwise have been routed
	///         to the node that does hold it
	public static boolean deliver(String aor, CloudEvent event) {
		Session holder = holderOf(event.getSubject());
		if (holder != null) {
			return send(holder, event);
		}
		boolean written = false;
		for (Session session : openSocketsOf(aor)) {
			written |= send(session, event);
		}
		return written;
	}

	/// Write `event` to every socket of `aor` except `except`: a call one device answered, ended on
	/// the others that were ringing.
	public static void deliverToOthers(String aor, Session except, CloudEvent event) {
		for (Session session : openSocketsOf(aor)) {
			if (!session.getId().equals(except.getId())) {
				send(session, event);
			}
		}
	}

	/// Write `event` to one socket.
	///
	/// @return false when the socket is closed or the write failed; a failed socket is dropped, so
	///         the next call fails over instead of writing into it
	public static boolean send(Session session, CloudEvent event) {
		if (!session.isOpen()) {
			return false;
		}
		try {
			String json = event.toJson();
			// One socket, potentially several call dialogs writing to it: serialize the sends, since
			// the WebSocket API forbids overlapping partial writes on one connection.
			synchronized (session) {
				session.getBasicRemote().sendText(json);
			}
			return true;
		} catch (IOException e) {
			// The browser vanished between the liveness check and the write.
			unregister(session);
			closeQuietly(session);
			return false;
		}
	}

	/// An empty WebSocket ping, the payload [#pingAll] sends.
	private static final java.nio.ByteBuffer PING = java.nio.ByteBuffer.allocate(0);

	/// Ping every browser connected to this node, so the socket never sits idle.
	///
	/// A proxy or load balancer in front of the gateway closes an upgraded connection that carries
	/// no frames for its idle timeout (nginx's `proxy_read_timeout` and OCI's load balancer both
	/// default to 60 seconds). A browser in a call can go that long with nothing to say, and losing
	/// the socket tears the call down. The browser answers a ping itself, so no page code is
	/// involved. A socket the ping cannot reach is dropped, as [#send] drops one.
	///
	/// @return how many sockets were pinged
	public static int pingAll() {
		int pinged = 0;
		for (Set<Session> sockets : BY_AOR.values()) {
			for (Session session : sockets) {
				if (!session.isOpen()) {
					continue;
				}
				try {
					synchronized (session) {
						session.getBasicRemote().sendPing(PING.duplicate());
					}
					pinged++;
				} catch (IOException | RuntimeException e) {
					unregister(session);
					closeQuietly(session);
				}
			}
		}
		return pinged;
	}

	private static List<Session> openSocketsOf(String aor) {
		Set<Session> sockets = (aor == null) ? null : BY_AOR.get(aor);
		if (sockets == null) {
			return Collections.emptyList();
		}
		List<Session> open = new ArrayList<>();
		for (Session session : sockets) {
			if (session.isOpen()) {
				open.add(session);
			}
		}
		return open;
	}

	private static void closeQuietly(Session session) {
		try {
			session.close();
		} catch (IOException ignore) {
			// best effort
		}
	}
}
