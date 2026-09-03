package org.vorpal.blade.framework.v3.security;

/// The answer to "may this caller do this to this record", and enough context
/// to audit it or tell the caller why not.
///
/// Modeled on `BrowserAuthenticator.Decision` in `services/webrtc`, which was
/// the first place in BLADE to treat an authorization outcome as a value rather
/// than a thrown exception or a bare boolean. The reason it is a value is the
/// audit record: a deny that cannot say *which rule* refused, and a permit that
/// cannot say which rule allowed, produce a log nobody can review.
///
/// Immutable, and constructed only through the factories, so there is no way to
/// build an allowed decision without also stating why it was allowed.
public final class AccessDecision {

	private final boolean allowed;
	private final DataPermission permission;
	private final String rule;
	private final String reason;
	private final boolean breakGlass;

	private AccessDecision(boolean allowed, DataPermission permission, String rule, String reason,
			boolean breakGlass) {
		this.allowed = allowed;
		this.permission = permission;
		this.rule = rule;
		this.reason = reason;
		this.breakGlass = breakGlass;
	}

	/// A rule matched and granted the permission.
	///
	/// @param permission what was asked for
	/// @param rule       the name of the rule that granted it, for the audit record
	public static AccessDecision permit(DataPermission permission, String rule) {
		return new AccessDecision(true, permission, rule, null, false);
	}

	/// Granted under [DataPermission#BREAKGLASS] rather than by an ordinary
	/// rule. Allowed like any permit, and flagged so the audit sink can record
	/// and alarm on it separately — an emergency path that looks identical to
	/// routine access in the log is not an emergency path.
	public static AccessDecision breakGlass(DataPermission permission, String rule) {
		return new AccessDecision(true, permission, rule, "break-glass", true);
	}

	/// Refused, with the reason a human will read in the audit log.
	public static AccessDecision deny(DataPermission permission, String reason) {
		return new AccessDecision(false, permission, null, reason, false);
	}

	public boolean isAllowed() {
		return allowed;
	}

	/// True when this was granted through the break-glass path. Never true on a
	/// deny.
	public boolean isBreakGlass() {
		return breakGlass;
	}

	/// What was asked for. Present on a deny as well as a permit — an audit log
	/// of refusals that does not say what was attempted is not worth keeping.
	public DataPermission getPermission() {
		return permission;
	}

	/// The rule that granted this, or null on a deny.
	public String getRule() {
		return rule;
	}

	/// Why it was refused, or null on an ordinary permit.
	public String getReason() {
		return reason;
	}

	@Override
	public String toString() {
		if (allowed) {
			return "permit[" + permission + " by " + rule + (breakGlass ? " BREAK-GLASS]" : "]");
		}
		return "deny[" + permission + ": " + reason + "]";
	}
}
