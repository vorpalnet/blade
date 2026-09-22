package org.vorpal.blade.applications.agent;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What an agent's "Update" does: records one disposition of one call. An
/// outcome (legitimate, suspected scam, confirmed scam, robocall, wrong number,
/// harassment), whether the caller's identity was verified, an action, and free
/// notes. A scam report is one disposition among the others, not a separate
/// verb, so every call gets the same record and the next pop for this number
/// can show what the last agent concluded.
///
/// One disposition fans out three ways, each best effort and independent:
///
///  1. **Block list**, only when the action asks for it: a `spam_numbers` row
///     proxy-block reads, with a treatment from the outcome and an expiry, so
///     the next call from this number is handled at the edge.
///  2. **Catalog label**, when the call has a recorded conversation: a
///     `BLADE_LABEL` row, the catalog's label store and the ground truth a fraud
///     score is calibrated against.
///  3. **The event**: one `agentDisposition` analytics event carrying every
///     field, keyed to the call's analytics session, so the sink stores it beside
///     the call and [Catalog#history] reads it back for the next pop. When the
///     call is not known to this node (a console dispositioning a call another
///     node popped) it goes out as a plain CloudEvent on the bus instead, which
///     is audited but not indexed by session.
///
/// Outcome maps to a treatment the same way proxy-block's sample does:
/// harassment goes to a review voicemail, robocall to a tarpit, scam is declined.
public final class DispositionService {

	private static final Logger LOG = Logger.getLogger(DispositionService.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// The analytics event name, and the `type` the sink files it under.
	public static final String EVENT = "agentDisposition";

	/// CloudEvent type for the fallback publish. Deliberately not under the
	/// framework's reserved `org.vorpal.blade.` prefix.
	public static final String FALLBACK_TYPE = "com.vorpal.agent.disposition";

	/// The one action that writes the block list.
	public static final String ACTION_BLOCK = "block";

	private final Catalog catalog;
	private final int expiryDays;
	private final Supplier<Analytics> analytics;

	public DispositionService(Catalog catalog, int expiryDays, Supplier<Analytics> analytics) {
		this.catalog = catalog;
		this.expiryDays = expiryDays;
		this.analytics = analytics;
	}

	/// The treatment an outcome maps to (see proxy-block's block-list treatments).
	public static String treatmentFor(String outcome) {
		if (outcome == null) {
			return "tarpit";
		}
		switch (outcome.toLowerCase(Locale.ROOT)) {
		case "harassment":
		case "abuse":
			return "review";
		case "robocall":
		case "spam":
			return "tarpit";
		case "scam":
		case "fraud":
		case "confirmed-scam":
		case "suspected-scam":
			return "decline";
		default:
			return "tarpit";
		}
	}

	/// One disposition as the page sends it.
	public static final class Disposition {
		public String vorpalId;
		public String ani;
		public String conversation;
		public String outcome;
		public String identity;
		public String action;
		public String notes;
		/// Reason codes, comma-separated: what tipped the agent off. The labeled
		/// data every signal is later thresholded against, so it is kept as
		/// fixed codes, not prose.
		public String reasons;
	}

	/// Record a disposition. Returns a small result the endpoint echoes to the
	/// console.
	public Result record(Disposition d, String agent) {
		String outcome = (d.outcome == null || d.outcome.isBlank()) ? "legitimate" : d.outcome.trim();
		String treatment = treatmentFor(outcome);
		boolean blocked = ACTION_BLOCK.equals(d.action) && d.ani != null
				&& catalog.blockNumber(d.ani, treatment, "agent:" + outcome, agent, expiryDays);
		boolean labelled = d.conversation != null && catalog.label(d.conversation, outcome, 1.0, "agent:" + agent);
		boolean published = publish(d, outcome, treatment, blocked, agent);
		return new Result(treatment, blocked, labelled, published);
	}

	private boolean publish(Disposition d, String outcome, String treatment, boolean blocked, String agent) {
		try {
			AgentConsoleRegistry.CallRef call = AgentConsoleRegistry.callRef(d.vorpalId);
			Analytics a = (analytics == null) ? null : analytics.get();
			if (call != null && a != null) {
				AnalyticsEvent event = new AnalyticsEvent(EVENT, call.vorpalId, call.startedAt);
				put(event, "outcome", outcome);
				put(event, "identity", d.identity);
				put(event, "action", d.action);
				put(event, "notes", d.notes);
				put(event, "reasons", d.reasons);
				put(event, "treatment", treatment);
				put(event, "blocked", String.valueOf(blocked));
				put(event, "agent", agent);
				put(event, "ani", d.ani);
				a.sendEvent(event);
				return true;
			}
			if (!EventBus.isReady()) {
				return false;
			}
			ObjectNode data = MAPPER.createObjectNode();
			data.put("outcome", outcome);
			if (d.identity != null) {
				data.put("identity", d.identity);
			}
			if (d.action != null) {
				data.put("action", d.action);
			}
			if (d.notes != null) {
				data.put("notes", d.notes);
			}
			if (d.reasons != null) {
				data.put("reasons", d.reasons);
			}
			if (d.ani != null) {
				data.put("ani", d.ani);
			}
			if (d.vorpalId != null) {
				data.put("vorpalId", d.vorpalId);
			}
			data.put("treatment", treatment);
			data.put("blocked", blocked);
			data.put("agent", agent == null ? "?" : agent);
			EventBus.publish(CloudEvent.create(FALLBACK_TYPE, "/agent", d.vorpalId != null ? d.vorpalId : d.ani, data));
			return true;
		} catch (Throwable t) {
			LOG.log(Level.FINE, "agent: disposition not published: " + t.getMessage());
			return false;
		}
	}

	private static void put(AnalyticsEvent event, String name, String value) {
		if (value != null && !value.isEmpty()) {
			event.addAttribute(name, value);
		}
	}

	/// What happened, for the console's one-line confirmation.
	public static final class Result {
		public final String treatment;
		public final boolean blocked;
		public final boolean labelled;
		public final boolean published;

		Result(String treatment, boolean blocked, boolean labelled, boolean published) {
			this.treatment = treatment;
			this.blocked = blocked;
			this.labelled = labelled;
			this.published = published;
		}
	}
}
