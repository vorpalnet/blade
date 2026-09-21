package org.vorpal.blade.applications.agent;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

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
/// owns both legs, the agent can act on a live call: a report ([ReportService])
/// blocks the number for next time, and (a later increment on this same base) a
/// divert re-points the ringing leg at voicemail.
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

	/// A report service over the current catalog. Null before startup finished.
	public static ReportService reports() {
		AgentSettings s = settings();
		Catalog cat = catalog();
		return (cat == null) ? null : new ReportService(cat, s.getDefaultReportExpiryDays());
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
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// Both legs are up.
	}

	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
		// A leg hung up; the framework tears the other down.
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// The agent's leg was refused (busy/decline). A voicemail fallback belongs
		// here in a later increment; for now the decline passes back to the caller.
		if (sipLogger.isLoggable(Level.FINE)) {
			sipLogger.fine("agent: agent leg declined " + outboundResponse.getStatus());
		}
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// The caller gave up before the agent answered.
	}

	// ============================================================ the screen-pop

	/// Build the pop and push it to the console(s). If the call names the agent it
	/// is delivered to (the `X-Agent-Id` header an ACD/CTI sets), the pop goes to
	/// that agent's console only; otherwise, or if that agent has no console open,
	/// it broadcasts (a supervisor's floor view, and never a silently lost pop).
	private void pop(SipServletRequest request) {
		String callId = request.getCallId();
		CallPop pop = CallPopBuilder.of(request, callId, CallerHistory.EMPTY);
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
				how = "agent " + agentId + " (" + reached + ")";
			} else {
				AgentConsoleRegistry.broadcast(json);
				how = (agentId == null ? "broadcast" : "broadcast (agent " + agentId + " not connected)");
			}
			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine("agent: pop callId=" + callId + " ani=" + pop.ani + " -> " + how);
			}
		} catch (Exception e) {
			sipLogger.log(Level.FINE, "agent: could not serialize/broadcast pop for " + callId, e);
		}
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
