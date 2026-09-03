package org.vorpal.blade.framework.v3.security;

import java.util.Map;

/// The one place that decides whether a caller may touch call content.
///
/// Every read path — a recording, a transcript, a call's headers, the access
/// log itself — asks this and acts on the answer. Nothing else decides. That is
/// the whole design constraint: an authorization rule that is enforced in four
/// places is enforced in three, and the fourth is the one an auditor finds.
///
/// ## What it will not do
///
/// It never consults [AdminRole]. `Admin`, `Operator`, `Deployer` and `Monitor`
/// govern the platform — configuration, deployment, tuning, logs — and grant no
/// access to content. The two vocabularies are disjoint by construction:
/// `DataPermission.fromName("Admin")` is null and `AdminRole.fromName("phi:play")`
/// is null, so no spelling of a platform role can arrive here as a permission.
/// A deployment that genuinely wants its administrators to hear calls says so
/// with a rule naming that group, in the open, where an access review can see
/// it.
///
/// ## Fail closed, and say why
///
/// A null policy, a null caller, an empty rule list, a rule that matches
/// nothing: all deny, each with a distinct reason so the audit record
/// distinguishes "no rule covered this" from "there was no policy loaded". The
/// second is a broken deployment and should look different from a working one
/// refusing a request. This follows `BrowserAuthenticator`'s rule that "we
/// could not read the rule" must never mean "there is no rule".
///
/// ## Constructed per policy load, not per request
///
/// The constructor calls [AccessPolicy#initialize], which resolves each rule's
/// permit strings into enums once. Build one when the configuration loads and
/// hold it; build a new one when the configuration changes. Instances are
/// immutable after construction and safe to share across request threads.
public final class AccessEvaluator {

	private final AccessPolicy policy;

	/// @param policy the loaded policy, or null — a null policy denies
	///               everything, which is what an unconfigured deployment
	///               should do
	public AccessEvaluator(AccessPolicy policy) {
		this.policy = policy;
		if (policy != null) {
			policy.initialize();
		}
	}

	/// May `subject` perform `permission` on the record described by `record`?
	///
	/// @param subject    the authenticated caller
	/// @param permission what they are trying to do
	/// @param record     the record's attributes — tenant, queue, team, agent,
	///                   classification and whatever else the index carries. The
	///                   keys are the deployment's own; [AccessRule#getMatch]
	///                   names them. May be null for a record with no attributes,
	///                   which only a rule with an empty match can reach.
	/// @return the decision, never null
	public AccessDecision evaluate(SubjectAttributes subject, DataPermission permission,
			Map<String, String> record) {

		if (permission == null) {
			return AccessDecision.deny(null, "no permission was asked for");
		}
		if (policy == null) {
			return AccessDecision.deny(permission, "no access policy is loaded");
		}
		if (subject == null) {
			return AccessDecision.deny(permission, "no authenticated caller");
		}
		if (policy.getRules().isEmpty()) {
			return AccessDecision.deny(permission, "the access policy has no rules");
		}

		for (AccessRule rule : policy.getRules()) {
			if (rule.permissions().contains(permission) && rule.appliesTo(subject) && rule.matches(subject, record)) {
				return AccessDecision.permit(permission, String.valueOf(rule));
			}
		}
		return AccessDecision.deny(permission, "no rule grants " + permission + " to this caller for this record");
	}

	/// The emergency path, taken only when the caller has asked for it.
	///
	/// It is a separate call rather than a fallback inside [#evaluate] on
	/// purpose. Break-glass that happens automatically whenever an ordinary
	/// evaluation denies is not break-glass; it is a group that can read
	/// everything, with a nicer name. Someone has to reach for it, and reaching
	/// for it is the thing the audit record captures.
	///
	/// The caller must still hold [DataPermission#BREAKGLASS] through an
	/// ordinary rule, so who may break glass is itself written in the policy.
	///
	/// @param justification why the caller says they need it, recorded verbatim.
	///                      Required: an emergency access with no stated reason
	///                      is refused, because the justification is most of what
	///                      makes the record reviewable afterwards.
	public AccessDecision breakGlass(SubjectAttributes subject, DataPermission permission,
			Map<String, String> record, String justification) {

		if (justification == null || justification.trim().isEmpty()) {
			return AccessDecision.deny(permission, "break-glass requires a stated justification");
		}
		AccessDecision holder = evaluate(subject, DataPermission.BREAKGLASS, record);
		if (!holder.isAllowed()) {
			return AccessDecision.deny(permission, "caller may not break glass: " + holder.getReason());
		}
		return AccessDecision.breakGlass(permission, holder.getRule() + " (break-glass: " + justification.trim() + ")");
	}
}
