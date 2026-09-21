package org.vorpal.blade.applications.agent;

import java.io.IOException;
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

	/// Open console sockets → the authenticated username behind each.
	private static final Map<Session, String> CONSOLES = new ConcurrentHashMap<>();

	private AgentConsoleRegistry() {
	}

	public static void add(Session session, String username) {
		CONSOLES.put(session, username == null ? "?" : username);
	}

	public static void remove(Session session) {
		CONSOLES.remove(session);
	}

	public static String userOf(Session session) {
		return CONSOLES.get(session);
	}

	public static int size() {
		return CONSOLES.size();
	}

	/// Push a JSON message to every open console, dropping any that have closed.
	public static void broadcast(String json) {
		for (Session s : CONSOLES.keySet()) {
			send(s, json);
		}
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
