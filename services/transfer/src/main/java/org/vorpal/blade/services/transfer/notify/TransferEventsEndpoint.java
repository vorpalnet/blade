package org.vorpal.blade.services.transfer.notify;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/// `wss://<host>/transfer/events`: a stream of [TransferNotice]s, one JSON
/// text frame per transfer event, for a client that would rather hold a
/// socket open than host a web service.
///
/// Query parameters narrow the stream; with none, a client gets every
/// transfer:
///
/// - `sessionKey`: a call's session key (what a REST request names), or its
///   Vorpal-ID
/// - `uid`: a REST request's `notification.uid`
///
/// The client may be connected to any engine, whichever handles the call:
/// [TransferNotices] puts each notice on the event bus, and every engine's
/// [TransferNoticeRelay] delivers it to that engine's own sockets. The socket
/// table is therefore per engine and never shared. The upgrade is covered by
/// the same security constraint as the REST API.
///
/// Every [#KEEPALIVE_SECONDS] each socket gets a ping frame. Transfers can
/// be minutes apart, and a proxy in front closes a socket that carries
/// nothing: NGINX's default `proxy_read_timeout` is 60 s, and behind it an
/// idle socket is closed (1006) at exactly 60 s. Any client
/// library answers a ping with a pong by itself, so the client writes no
/// keepalive code. The pings run on one daemon thread that
/// [TransferNoticeRelay] starts and stops with the application, not on a
/// managed scheduled executor, whose shutdown has hung a redeploy.
@ServerEndpoint("/events")
public class TransferEventsEndpoint {

	/// Seconds between pings: half of NGINX's default read timeout.
	static final int KEEPALIVE_SECONDS = 30;

	private static final Map<Session, Filter> SOCKETS = new ConcurrentHashMap<>();

	private static ScheduledExecutorService keepalive;

	/// What one client asked for.
	static final class Filter {
		final String sessionKey;
		final String uid;

		Filter(String sessionKey, String uid) {
			this.sessionKey = blank(sessionKey) ? null : sessionKey;
			this.uid = blank(uid) ? null : uid;
		}

		boolean matches(TransferNotice notice) {
			if (sessionKey != null) {
				boolean hit = sessionKey.equals(notice.vorpalId)
						|| (notice.sessionKeys != null && notice.sessionKeys.contains(sessionKey))
						|| (notice.request != null && sessionKey.equals(notice.request.sessionKey));
				if (!hit) {
					return false;
				}
			}
			return uid == null || uid.equals(notice.uid);
		}

		private static boolean blank(String s) {
			return s == null || s.trim().isEmpty();
		}
	}

	@OnOpen
	public void onOpen(Session session) {
		// The container closes an idle socket after 30 s by default; transfers are rare.
		session.setMaxIdleTimeout(0);
		Map<String, List<String>> params = session.getRequestParameterMap();
		SOCKETS.put(session, new Filter(first(params, "sessionKey"), first(params, "uid")));
	}

	@OnClose
	public void onClose(Session session, CloseReason reason) {
		SOCKETS.remove(session);
	}

	@OnError
	public void onError(Session session, Throwable error) {
		SOCKETS.remove(session);
		Logger log = SettingsManager.getSipLogger();
		if (log != null) {
			log.fine("TransferEventsEndpoint - socket error: " + error);
		}
	}

	/// Sends `json` to every socket on this engine whose filter matches.
	static void deliver(TransferNotice notice, String json) {
		for (Map.Entry<Session, Filter> socket : SOCKETS.entrySet()) {
			Session session = socket.getKey();
			if (!session.isOpen()) {
				SOCKETS.remove(session);
				continue;
			}
			if (socket.getValue().matches(notice)) {
				session.getAsyncRemote().sendText(json);
			}
		}
	}

	/// Starts the pings; called when the application starts.
	static synchronized void startKeepalive() {
		if (keepalive == null) {
			keepalive = Executors.newSingleThreadScheduledExecutor(task -> {
				Thread thread = new Thread(task, "transfer-events-keepalive");
				thread.setDaemon(true);
				return thread;
			});
			keepalive.scheduleWithFixedDelay(TransferEventsEndpoint::ping, KEEPALIVE_SECONDS,
					KEEPALIVE_SECONDS, TimeUnit.SECONDS);
		}
	}

	/// Stops the pings; called when the application stops.
	static synchronized void stopKeepalive() {
		if (keepalive != null) {
			keepalive.shutdownNow();
			keepalive = null;
		}
	}

	/// One ping frame to every open socket. A socket that fails is dropped;
	/// nothing here may throw, or the executor stops scheduling.
	static void ping() {
		for (Session session : SOCKETS.keySet()) {
			try {
				if (session.isOpen()) {
					session.getAsyncRemote().sendPing(ByteBuffer.allocate(0));
				} else {
					SOCKETS.remove(session);
				}
			} catch (Exception e) {
				SOCKETS.remove(session);
			}
		}
	}

	/// The open sockets on this engine, for tests and diagnostics.
	static Set<Session> sockets() {
		return SOCKETS.keySet();
	}

	private static String first(Map<String, List<String>> params, String name) {
		List<String> values = (params != null) ? params.get(name) : null;
		return (values != null && !values.isEmpty()) ? values.get(0) : null;
	}
}
