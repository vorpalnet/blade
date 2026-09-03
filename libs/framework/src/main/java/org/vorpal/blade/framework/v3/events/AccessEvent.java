package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.security.AccessDecision;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// One access-control decision, on its way to the audit log.
///
/// Built from an [org.vorpal.blade.framework.v3.security.AccessEvaluator]
/// answer and published whichever way the answer went. Emitting only the grants
/// would produce a log that cannot show attempted overreach, which is most of
/// what an access review is looking for.
///
/// ## What it must not carry
///
/// The identifier of the thing reached for, never the thing itself. An audit
/// record that quotes the transcript it is recording access to has published
/// the content to every reader of the audit log — including the readers who were
/// denied it. There is no field here to put content in, and that is deliberate
/// rather than incidental.
///
/// ## Why it is not an [AnalyticsEvent]
///
/// Analytics records what a call did. This records what a person did. They have
/// different readers, different retention, and different integrity
/// requirements: the sink for this one should hold INSERT and SELECT and
/// nothing else, so that the people it records cannot rewrite it.
public class AccessEvent implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String actor;
	private final String action;
	private final String resourceKind;
	private final String resourceId;
	private final String decision;
	private final String rule;
	private final String reason;
	private final boolean allowed;

	private String sourceAddress;

	/// @param subject      the caller the decision was about
	/// @param result       what [org.vorpal.blade.framework.v3.security.AccessEvaluator] answered
	/// @param resourceKind what sort of thing was reached for, e.g. `recording`
	/// @param resourceId   which one — an identifier, never content
	public AccessEvent(SubjectAttributes subject, AccessDecision result, String resourceKind, String resourceId) {
		this.actor = (subject == null) ? null : subject.name();
		this.action = (result == null || result.getPermission() == null) ? null
				: result.getPermission().permissionName();
		this.resourceKind = resourceKind;
		this.resourceId = resourceId;
		this.allowed = (result != null) && result.isAllowed();
		this.rule = (result == null) ? null : result.getRule();
		this.reason = (result == null) ? null : result.getReason();
		if (result == null) {
			this.decision = "deny";
		} else if (result.isBreakGlass()) {
			this.decision = "breakglass";
		} else {
			this.decision = result.isAllowed() ? "permit" : "deny";
		}
	}

	/// The client address the request arrived from, when the caller knows it.
	public AccessEvent from(String address) {
		this.sourceAddress = address;
		return this;
	}

	/// [BladeEventTypes#ACCESS_PERMITTED] or [BladeEventTypes#ACCESS_DENIED].
	///
	/// A break-glass grant is a *permitted* event carrying `decision` of
	/// `breakglass`, not a third type. It is a grant, and a subscriber counting
	/// grants must not have to know about a special case to count it; a
	/// subscriber alarming on emergency access selects on the field.
	public String type() {
		return allowed ? BladeEventTypes.ACCESS_PERMITTED : BladeEventTypes.ACCESS_DENIED;
	}

	/// The envelope, ready to publish.
	///
	/// No `subject`: an access record is scoped to a person and a resource, not
	/// to a call. Where the resource happens to be a call the correlator is in
	/// `resourceId`, and putting it in the CloudEvents `subject` as well would
	/// claim this event belongs to the call's stream, which it does not — it
	/// belongs to the actor's.
	public CloudEvent toCloudEvent(String source) {
		ObjectNode data = MAPPER.createObjectNode();
		put(data, "actor", actor);
		put(data, "action", action);
		put(data, "resourceKind", resourceKind);
		put(data, "resourceId", resourceId);
		put(data, "decision", decision);
		put(data, "rule", rule);
		put(data, "reason", reason);
		put(data, "sourceAddress", sourceAddress);
		String type = type();
		return CloudEvent.create(type, source, null, data, BladeEventCatalog.versionOf(type));
	}

	private static void put(ObjectNode node, String name, String value) {
		if (value != null) {
			node.put(name, value);
		}
	}

	public String getActor() {
		return actor;
	}

	public String getAction() {
		return action;
	}

	public String getResourceKind() {
		return resourceKind;
	}

	public String getResourceId() {
		return resourceId;
	}

	public String getDecision() {
		return decision;
	}

	public String getRule() {
		return rule;
	}

	public String getReason() {
		return reason;
	}

	public String getSourceAddress() {
		return sourceAddress;
	}

	public boolean isAllowed() {
		return allowed;
	}

	@Override
	public String toString() {
		return "access[" + decision + " " + actor + " " + action + " " + resourceKind + ":" + resourceId + "]";
	}
}
