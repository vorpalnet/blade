package org.vorpal.blade.applications.agent;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What an agent's "report" does. One report fans out three ways, each best
/// effort and independent:
///
///  1. **Block list** — a `spam_numbers` row proxy-block reads, with a treatment
///     from the category and an expiry, so the next call from this number is
///     handled at the edge.
///  2. **Catalog label** — a `BLADE_LABEL` row on the reported conversation, the
///     catalog's label store (first writer) and the ground-truth a fraud score
///     is calibrated against.
///  3. **Event** — one CloudEvent on the bus for audit and any other subscriber.
///
/// Category maps to a treatment the same way proxy-block's sample does:
/// harassment goes to a review voicemail, robocall to a tarpit, scam is declined.
public final class ReportService {

	private static final Logger LOG = Logger.getLogger(ReportService.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// CloudEvent type for an agent report. Deliberately not under the framework's
	/// reserved `org.vorpal.blade.` prefix, so it is an app fact, not a spoofable
	/// framework event.
	public static final String REPORT_TYPE = "com.vorpal.agent.report";

	private final Catalog catalog;
	private final int expiryDays;

	public ReportService(Catalog catalog, int expiryDays) {
		this.catalog = catalog;
		this.expiryDays = expiryDays;
	}

	/// The treatment a category maps to (see proxy-block's block-list treatments).
	public static String treatmentFor(String category) {
		if (category == null) {
			return "tarpit";
		}
		switch (category.toLowerCase(Locale.ROOT)) {
		case "harassment":
		case "abuse":
			return "review";
		case "robocall":
		case "spam":
			return "tarpit";
		case "scam":
		case "fraud":
			return "decline";
		default:
			return "tarpit";
		}
	}

	/// Record a report. Returns a small result the endpoint echoes to the console.
	public Result record(String ani, String conversation, String callId, String category, String agent) {
		String treatment = treatmentFor(category);
		boolean blocked = ani != null && catalog.blockNumber(ani, treatment,
				"agent-report:" + category, agent, expiryDays);
		boolean labelled = conversation != null
				&& catalog.label(conversation, category == null ? "spam" : category, 1.0, "agent:" + agent);
		boolean published = publish(ani, conversation, callId, category, treatment, agent);
		return new Result(treatment, blocked, labelled, published);
	}

	private boolean publish(String ani, String conversation, String callId, String category, String treatment,
			String agent) {
		try {
			if (!EventBus.isReady()) {
				return false;
			}
			ObjectNode data = MAPPER.createObjectNode();
			if (ani != null) {
				data.put("ani", ani);
			}
			if (conversation != null) {
				data.put("conversation", conversation);
			}
			if (callId != null) {
				data.put("callId", callId);
			}
			data.put("category", category == null ? "spam" : category);
			data.put("treatment", treatment);
			data.put("agent", agent == null ? "?" : agent);
			CloudEvent event = CloudEvent.create(REPORT_TYPE, "/agent", callId != null ? callId : ani, data);
			EventBus.publish(event);
			return true;
		} catch (Throwable t) {
			LOG.log(Level.WARNING, "agent: report event not published: " + t.getMessage());
			return false;
		}
	}

	/// Outcome of a report, each leg independent so the console can say which took.
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
