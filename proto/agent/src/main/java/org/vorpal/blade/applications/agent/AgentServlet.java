package org.vorpal.blade.applications.agent;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The engine-tier SIP side of the Agent Console: a v3 B2BUA that bridges the
/// caller to the human agent, and pops the agent's screen as the call arrives.
///
/// A B2BUA, not a proxy (v3 does not do proxy): it answers the caller and places
/// a second call to the agent, holding both legs. Two things follow from that.
/// The pop is built and pushed the moment the second leg starts, so it reaches
/// the agent's screen while their phone is still ringing. And, because the app
/// owns both legs, the agent can act on a live call: a disposition
/// ([DispositionService]) records what they concluded and can block the number
/// for next time, and (a later increment on this same base) a divert re-points
/// the ringing leg at voicemail.
///
/// Nothing the pop does can fail the call. History is best-effort ([Catalog]) and
/// the push is best-effort ([AgentConsoleRegistry]); a screen-pop that throws is
/// swallowed and the bridge proceeds. The screening and any block already
/// happened upstream at the edge; this app shows the agent what those decided and
/// gives them a one-click way to report what got through.
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class AgentServlet extends B2buaServlet {

	private static final long serialVersionUID = 1L;

	/// How many recent calls to carry in a pop.
	private static final int RECENT_LIMIT = 5;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static SettingsManager<AgentSettings> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, AgentSettings.class, new AgentSettingsSample());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		if (settingsManager != null) {
			settingsManager.unregister();
		}
	}

	/// The live settings, or null before startup finished.
	public static AgentSettings settings() {
		return (settingsManager == null) ? null : settingsManager.getCurrent();
	}

	/// A catalog built from the current settings (cheap; holds only names, does its
	/// JNDI lookup per operation). Null before startup finished.
	public static Catalog catalog() {
		AgentSettings s = settings();
		return (s == null) ? null : new Catalog(s.getDataSource(), s.getHistoryTable(), s.getSpamTable());
	}

	/// A disposition service over the current catalog. Null before startup finished.
	public static DispositionService dispositions() {
		AgentSettings s = settings();
		Catalog cat = catalog();
		return (cat == null) ? null : new DispositionService(cat, s.getDefaultReportExpiryDays(),
				SettingsManager::getAnalytics);
	}

	// ============================================================ the B2BUA bridge

	/// The B2BUA is creating the second leg (to the agent). Build and push the pop
	/// from the inbound call, and point this outbound leg at the agent's URI. The
	/// caller is bridged to whatever answers.
	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		SipServletRequest inbound = getIncomingRequest(outboundRequest);
		SipServletRequest source = (inbound != null) ? inbound : outboundRequest;

		try {
			pop(source);
		} catch (Throwable t) {
			// The pop is a convenience; never let it stop the call.
			sipLogger.log(Level.FINE, "agent: screen-pop failed, bridging anyway", t);
		}

		URI target = agentTarget(source);
		if (target != null) {
			outboundRequest.setRequestURI(target);
		}
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundRequest, "AgentServlet.callStarted - ringing agent at " + target);
		}
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		// The agent (or voicemail) answered; the framework bridges the media.
		state(vorpalIdOf(outboundResponse.getApplicationSession()), "talking");
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// Both legs are up.
	}

	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
		// A leg hung up; the framework tears the other down. No more updates
		// will find this call, so stop remembering whose screen it was on.
		String vorpalId = vorpalIdOf(request);
		state(vorpalId, "ended");
		AgentConsoleRegistry.forget(vorpalId);
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// The agent's leg was refused (busy/decline). A voicemail fallback belongs
		// here in a later increment; for now the decline passes back to the caller.
		if (sipLogger.isLoggable(Level.FINE)) {
			sipLogger.fine("agent: agent leg declined " + outboundResponse.getStatus());
		}
		state(vorpalIdOf(outboundResponse.getApplicationSession()), "declined");
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// The caller gave up before the agent answered.
		String vorpalId = vorpalIdOf(outboundRequest);
		state(vorpalId, "abandoned");
		AgentConsoleRegistry.forget(vorpalId);
	}

	// ============================================================ the screen-pop

	/// Build the pop and push it to the console(s). If the call names the agent it
	/// is delivered to (the `X-Agent-Id` header an ACD/CTI sets), the pop goes to
	/// that agent's console only; otherwise, or if that agent has no console open,
	/// it broadcasts (a supervisor's floor view, and never a silently lost pop).
	private void pop(SipServletRequest request) {
		String callId = request.getCallId();
		CallPop pop = CallPopBuilder.of(request, callId, CallerHistory.EMPTY);
		// The card's identity: the Vorpal-ID in the same hex form every bus event
		// carries, so a later update about this call finds its card.
		pop.vorpalId = vorpalIdOf(request);
		try {
			SipApplicationSession app = request.getApplicationSession();
			AgentConsoleRegistry.rememberCall(pop.vorpalId,
					new AgentConsoleRegistry.CallRef(Analytics.getVorpalId(app), Analytics.getCallStartedAt(app), pop.ani));
		} catch (Throwable t) {
			// No analytics identity: a disposition still publishes, just not by session.
		}
		Catalog cat = catalog();
		if (cat != null && pop.ani != null) {
			pop.history = cat.history(pop.ani, RECENT_LIMIT);
		}
		String agentId = header(request, "X-Agent-Id");
		try {
			ObjectNode msg = MAPPER.createObjectNode();
			msg.put("t", "pop");
			msg.set("pop", MAPPER.valueToTree(pop));
			String json = msg.toString();
			int reached = (agentId == null) ? 0 : AgentConsoleRegistry.sendToUser(agentId, json);
			String how;
			if (reached > 0) {
				// Remember who has this call, so its updates go to the same screen.
				AgentConsoleRegistry.remember(pop.vorpalId, agentId);
				how = "agent " + agentId + " (" + reached + ")";
			} else {
				int sent = AgentConsoleRegistry.broadcast(json);
				how = (agentId == null ? "broadcast" : "broadcast (agent " + agentId + " not connected)") + " to "
						+ sent + " console(s)";
			}
			// One line per pop, at INFO: it is the demo's whole point, and a pop that
			// reached nobody is the first thing to look for.
			sipLogger.info("agent: pop vorpalId=" + pop.vorpalId + " ani=" + pop.ani + " risk=" + pop.riskBand
					+ " -> " + how);
		} catch (Exception e) {
			sipLogger.log(Level.FINE, "agent: could not serialize/broadcast pop for " + pop.vorpalId, e);
		}
	}

	/// The call's Vorpal-ID as `%08X` hex — the form the bus events carry — or
	/// null before the framework has assigned one.
	private static String vorpalIdOf(SipServletRequest request) {
		return vorpalIdOf(request.getApplicationSession());
	}

	private static String vorpalIdOf(SipApplicationSession app) {
		try {
			Long id = Analytics.getVorpalId(app);
			return (id == null) ? null : String.format("%08X", id);
		} catch (Throwable t) {
			return null;
		}
	}

	/// Tell the card what the call is doing: ringing (the pop itself), talking,
	/// ended, declined, abandoned. Straight from this app's own callbacks to the
	/// socket; no bus round-trip for a fact this app already holds.
	private static void state(String vorpalId, String state) {
		if (vorpalId == null) {
			return;
		}
		ObjectNode frame = MAPPER.createObjectNode();
		frame.put("t", "update");
		frame.put("vorpalId", vorpalId);
		frame.put("state", state);
		String where = AgentConsoleRegistry.sendToCall(vorpalId, frame.toString());
		AgentConsoleRegistry.log("agent: state vorpalId=" + vorpalId + " " + state + " -> " + where);
	}

	/// Where to ring the agent: the configured agent URI, or the request URI when
	/// none is set (so the app is harmless with no config). Null only if neither
	/// parses.
	private URI agentTarget(SipServletRequest request) {
		AgentSettings s = settings();
		String uri = (s == null) ? null : s.getAgentUri();
		if (uri != null && !uri.isBlank()) {
			try {
				return getSipFactory().createURI(uri);
			} catch (Exception e) {
				sipLogger.log(Level.WARNING, "agent: bad agentUri '" + uri + "', using request URI", e);
			}
		}
		return request.getRequestURI();
	}

	private static String header(SipServletRequest r, String name) {
		try {
			String v = r.getHeader(name);
			return (v == null || v.trim().isEmpty()) ? null : v.trim();
		} catch (Throwable t) {
			return null;
		}
	}
}
