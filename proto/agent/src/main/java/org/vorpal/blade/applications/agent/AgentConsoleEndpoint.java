package org.vorpal.blade.applications.agent;

import java.security.Principal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.vorpal.blade.framework.v3.security.JwtIdentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The console's one channel, both directions.
///
/// A signed-in agent's browser opens a single socket. Screen-pops arrive on it
/// as calls come in ([AgentServlet] pushes them through [AgentConsoleRegistry]);
/// the agent's one action, reporting a call, goes back up the same socket. There
/// is no second protocol: the page already holding a socket open does not also
/// make HTTP calls.
///
/// ## Identity
///
/// Authentication is OpenID Connect, handled by the `OidcLoginFilter` in front
/// of this WAR (see `web.xml`): the socket's users are corporate agents with no
/// WebLogic account. The filter runs on the upgrade request like any other, so
/// an unauthenticated upgrade never reaches [#onOpen], and an authenticated one
/// arrives with the [JwtIdentity] as its principal, carrying the token's group
/// claims. Cross-site upgrades are refused before this class by the fleet-wide
/// same-origin filter, so there is no origin check here.
///
/// ## Who may report
///
/// Watching needs only a signed-in identity. Reporting is gated against the
/// identity's groups: a report frame is honored only from a member of one of
/// [AgentSettings#getReportGroups]. A container-role check is deliberately not
/// used, because it cannot reach a single WebSocket frame. Attribution needs no
/// trust in the client: the reporting agent is the socket's authenticated
/// principal, never a field the browser sent.
@ServerEndpoint("/console")
public class AgentConsoleEndpoint {

	private static final Logger LOG = Logger.getLogger(AgentConsoleEndpoint.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@OnOpen
	public void onOpen(Session session) {
		Principal principal = session.getUserPrincipal();
		if (principal == null) {
			// OidcLoginFilter should have refused the upgrade; refuse defensively.
			close(session, "sign-in required");
			return;
		}
		// The container closes an idle WebSocket after 30 s by default, and a
		// console is idle for as long as no call arrives: without this the page
		// flickered to "reconnecting" every half minute. Liveness comes from the
		// registry's ping instead (AgentConsoleRegistry.ping), which also keeps a
		// reverse proxy's idle cutoff from doing the same thing a minute later.
		session.setMaxIdleTimeout(0);
		AgentConsoleRegistry.add(session, principal.getName());
		// Tell the page who it is and whether its report button should be live.
		ObjectNode hello = MAPPER.createObjectNode();
		hello.put("t", "hello");
		hello.put("user", principal.getName());
		hello.put("mayReport", mayReport(session));
		AgentConsoleRegistry.send(session, hello.toString());
		LOG.fine("agent: console open for " + principal.getName() + " (" + AgentConsoleRegistry.size() + " open)");
	}

	/// A message from the browser. The only kind is a report; anything else is
	/// ignored. A report blocks the number, labels the conversation and puts the
	/// report on the bus ([ReportService]), then the result goes back to this one
	/// socket.
	@OnMessage
	public void onMessage(String text, Session session) {
		Principal principal = session.getUserPrincipal();
		if (principal == null) {
			return; // never happens after onOpen, but never act without a principal
		}
		JsonNode in;
		try {
			in = MAPPER.readTree(text == null ? "{}" : text);
		} catch (Exception e) {
			sendResult(session, null, false, "malformed message", null);
			return;
		}

		String type = str(in, "t");
		if (!"report".equals(type)) {
			return; // the page speaks only "report" upstream
		}

		String ani = str(in, "ani");
		String conversation = str(in, "conversation");
		// The call's identity is its Vorpal-ID, the same key the card carries.
		String vorpalId = str(in, "vorpalId");
		String category = str(in, "category");

		if (!mayReport(session)) {
			sendResult(session, vorpalId, false, "your account is not permitted to report calls", null);
			return;
		}
		ReportService reports = AgentServlet.reports();
		if (reports == null) {
			sendResult(session, vorpalId, false, "settings not loaded", null);
			return;
		}
		if (ani == null && conversation == null) {
			sendResult(session, vorpalId, false, "ani or conversation is required", null);
			return;
		}

		ReportService.Result result = reports.record(ani, conversation, vorpalId, category, principal.getName());
		sendResult(session, vorpalId, true, null, result);
	}

	@OnClose
	public void onClose(Session session) {
		AgentConsoleRegistry.remove(session);
	}

	@OnError
	public void onError(Session session, Throwable t) {
		LOG.log(Level.FINE, "agent: console socket error", t);
		AgentConsoleRegistry.remove(session);
	}

	/// May the signed-in user on this socket report a call? A report group is one
	/// of the identity provider's groups (or roles), named in settings. With none
	/// configured, any signed-in user may report. When the identity is the OpenID
	/// one ([JwtIdentity]) its group claims decide; under the container-login
	/// fallback the caller is an administrator with a real account, so a report
	/// is allowed.
	private static boolean mayReport(Session session) {
		AgentSettings s = AgentServlet.settings();
		String cfg = (s == null) ? null : s.getReportGroups();
		Principal principal = session.getUserPrincipal();
		if (principal instanceof JwtIdentity) {
			JwtIdentity id = (JwtIdentity) principal;
			return mayReport(cfg, id.groups(), id.roles(), true);
		}
		// Container-login fallback: a real WebLogic account, not the OpenID path.
		return mayReport(cfg, null, null, false);
	}

	/// The report gate, as a pure decision so it can be tested without a socket.
	/// With no groups configured, anyone signed in may report. Otherwise an
	/// OpenID identity must hold one of the groups (or roles); a non-OpenID
	/// caller is the container-login fallback and is allowed. Matching ignores
	/// case.
	static boolean mayReport(String reportGroupsCsv, Set<String> groups, Set<String> roles, boolean openIdIdentity) {
		Set<String> allowed = new HashSet<>();
		if (reportGroupsCsv != null) {
			for (String part : reportGroupsCsv.split(",")) {
				String g = part.trim().toLowerCase(Locale.ROOT);
				if (!g.isEmpty()) {
					allowed.add(g);
				}
			}
		}
		if (allowed.isEmpty()) {
			return true;
		}
		if (!openIdIdentity) {
			return true;
		}
		if (containsAny(allowed, groups) || containsAny(allowed, roles)) {
			return true;
		}
		return false;
	}

	private static boolean containsAny(Set<String> allowedLower, Set<String> values) {
		if (values == null) {
			return false;
		}
		for (String v : values) {
			if (v != null && allowedLower.contains(v.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/// Send a report outcome back to the one socket that asked. The Vorpal-ID is
	/// echoed so the page can match the result to the card that raised it.
	private void sendResult(Session session, String vorpalId, boolean ok, String error, ReportService.Result result) {
		ObjectNode msg = MAPPER.createObjectNode();
		msg.put("t", "reportResult");
		msg.put("ok", ok);
		if (vorpalId != null) {
			msg.put("vorpalId", vorpalId);
		}
		if (error != null) {
			msg.put("error", error);
		}
		if (result != null) {
			msg.put("treatment", result.treatment);
			msg.put("blocked", result.blocked);
			msg.put("labelled", result.labelled);
			msg.put("published", result.published);
		}
		AgentConsoleRegistry.send(session, msg.toString());
	}

	private static String str(JsonNode node, String field) {
		JsonNode v = node.get(field);
		if (v == null || v.isNull()) {
			return null;
		}
		String s = v.asText().trim();
		return s.isEmpty() ? null : s;
	}

	private static void close(Session session, String reason) {
		try {
			session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
		} catch (Exception e) {
			LOG.log(Level.FINE, "agent: closing refused console", e);
		}
	}
}
