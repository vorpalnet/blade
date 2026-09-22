package org.vorpal.blade.applications.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.Session;

/// The consoles currently watching, and how to push to them.
///
/// v1 pushes every screen-pop to every open console: for a single-agent demo
/// that is the agent's screen, and for a supervisor it is the floor view.
/// Routing a pop to the one agent an ACD assigned the call to is a deferred
/// step (see the agent-app questions log); the registry already tracks each
/// session's authenticated user so that routing, and report attribution, have
/// what they need.
public final class AgentConsoleRegistry {

	private static final Logger LOG = Logger.getLogger(AgentConsoleRegistry.class.getName());

	/// One line in the APPLICATION's log (agent.<n>.log), where the SIP side already
	/// writes, so a console opening and a pop or update reaching it read as one
	/// story. The framework's logger is installed by the SIP servlet; a WebSocket
	/// can open before that during deployment, so fall back to JUL rather than NPE.
	static void log(String message) {
		org.vorpal.blade.framework.v2.logging.Logger app = org.vorpal.blade.framework.Callflow.getSipLogger();
		if (app != null) {
			app.info(message);
		} else {
			LOG.info(message);
		}
	}

	/// Open console sockets → the authenticated username behind each.
	private static final Map<Session, String> CONSOLES = new ConcurrentHashMap<>();

	/// Which agent's console received the pop for a call, by Vorpal-ID, so a
	/// later update about that call (a risk verdict from the bus) lands on the
	/// same screen. Only targeted pops are remembered; a broadcast pop has no
	/// one agent, and its updates broadcast too. Forgotten when the call ends.
	private static final Map<String, String> AGENT_FOR_CALL = new ConcurrentHashMap<>();

	/// A popped call's analytics identity, so a disposition sent from a console
	/// minutes after the call ended can still be filed against the call's
	/// session: the Vorpal-ID as the number the framework assigned, and when the
	/// call started, the two the sink hashes a session from.
	public static final class CallRef {
		public final Long vorpalId;
		public final Date startedAt;
		public final String ani;

		public CallRef(Long vorpalId, Date startedAt, String ani) {
			this.vorpalId = vorpalId;
			this.startedAt = startedAt;
			this.ani = ani;
		}
	}

	/// The last thousand popped calls by hex Vorpal-ID. Never forgotten on call
	/// end (wrap-up happens after the hang-up); bounded instead.
	private static final Map<String, CallRef> CALLS = Collections
			.synchronizedMap(new LinkedHashMap<String, CallRef>(256, 0.75f, false) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, CallRef> eldest) {
					return size() > 1000;
				}
			});

	public static void rememberCall(String vorpalIdHex, CallRef ref) {
		if (vorpalIdHex != null && ref != null) {
			CALLS.put(vorpalIdHex, ref);
		}
	}

	public static CallRef callRef(String vorpalIdHex) {
		return (vorpalIdHex == null) ? null : CALLS.get(vorpalIdHex);
	}

	private AgentConsoleRegistry() {
	}

	public static void remember(String vorpalId, String username) {
		if (vorpalId != null && username != null) {
			AGENT_FOR_CALL.put(vorpalId, username);
		}
	}

	public static void forget(String vorpalId) {
		if (vorpalId != null) {
			AGENT_FOR_CALL.remove(vorpalId);
		}
	}

	/// Push an update about a call to the console holding it: the agent who got
	/// the pop if one was recorded and is still connected, else every console
	/// (a supervisor's floor view, and never a silently lost update). Returns a
	/// one-line account of where it went, for the log.
	public static String sendToCall(String vorpalId, String json) {
		String user = (vorpalId == null) ? null : AGENT_FOR_CALL.get(vorpalId);
		int reached = (user == null) ? 0 : sendToUser(user, json);
		if (reached > 0) {
			return "agent " + user + " (" + reached + ")";
		}
		int sent = broadcast(json);
		return (user == null ? "broadcast" : "broadcast (agent " + user + " not connected)") + " to " + sent
				+ " console(s)";
	}

	public static void add(Session session, String username) {
		CONSOLES.put(session, username == null ? "?" : username);
		log("agent: console open for " + username + " (" + CONSOLES.size() + " open)");
	}

	public static void remove(Session session) {
		String user = CONSOLES.remove(session);
		if (user != null) {
			log("agent: console closed for " + user + " (" + CONSOLES.size() + " open)");
		}
	}

	public static String userOf(Session session) {
		return CONSOLES.get(session);
	}

	public static int size() {
		return CONSOLES.size();
	}

	/// Push a JSON message to every open console, dropping any that have closed.
	/// Returns how many it was sent to.
	public static int broadcast(String json) {
		int sent = 0;
		for (Session s : CONSOLES.keySet()) {
			send(s, json);
			sent++;
		}
		return sent;
	}

	/// Push to the console(s) of one agent, by username. Returns how many sockets
	/// it reached; 0 means that agent has no console open (the caller then decides
	/// whether to fall back to a broadcast). A supervisor watching under their own
	/// name is not this agent, so a targeted pop stays with the assigned agent.
	public static int sendToUser(String username, String json) {
		if (username == null) {
			return 0;
		}
		int reached = 0;
		for (Map.Entry<Session, String> e : CONSOLES.entrySet()) {
			if (username.equalsIgnoreCase(e.getValue())) {
				send(e.getKey(), json);
				reached++;
			}
		}
		return reached;
	}

	/// Seconds between keep-alive pings. Under any proxy's idle cutoff (nginx
	/// defaults to 60 s) and the container's own 30 s, which the endpoint
	/// disables anyway.
	public static final int PING_SECONDS = 25;

	/// Ping every open console. A pong is traffic in both directions, so no idle
	/// timer between the browser and this server fires while a console is merely
	/// waiting for a call. A socket that cannot be pinged is gone: drop it.
	public static void ping() {
		for (Session s : CONSOLES.keySet()) {
			try {
				if (s.isOpen()) {
					s.getBasicRemote().sendPing(ByteBuffer.allocate(0));
				} else {
					CONSOLES.remove(s);
				}
			} catch (Exception e) {
				LOG.log(Level.FINE, "agent: console ping failed, dropping socket: " + e.getMessage());
				CONSOLES.remove(s);
			}
		}
	}

	public static void send(Session session, String json) {
		try {
			if (session != null && session.isOpen()) {
				session.getBasicRemote().sendText(json);
			}
		} catch (IOException e) {
			LOG.log(Level.FINE, "agent: console send failed, dropping socket: " + e.getMessage());
			CONSOLES.remove(session);
		}
	}
}
