package org.vorpal.blade.framework.v3.security;

/// What a caller may do with call content — audio, transcripts, and the
/// call-identifying data around them.
///
/// ## Why this is not an [AdminRole]
///
/// [AdminRole] governs the *platform*: config, deploy, tuning, logs. This
/// governs the *content*. They are orthogonal on purpose, and the rule that
/// makes them useful is that **a platform role grants no data permission**. A
/// read-only `Monitor` watching a cluster has no job-function reason to hear a
/// patient's call, and a supervisor who may hear their team's calls has no
/// reason to redeploy an application. Collapsing the two vocabularies is how
/// systems end up granting call audio to whoever can already read a dashboard.
///
/// ## Why it is a ladder rather than a flag
///
/// A single "may access recordings" boolean cannot express the thing an access
/// review actually asks for: the *least* access that does the job. Knowing a
/// call exists, reading what was said, hearing the caller's voice, and walking
/// out of the building with a file are four different disclosures with four
/// different consequences, so they are four different permissions. A rule
/// grants the rungs it needs and no more.
///
/// ## These names are BLADE's; the job titles are the customer's
///
/// Nothing here is a job title, a department, or a group. The customer's
/// identity provider owns those, and `JwtAuthConfig.roleMappings` is where a
/// group name is mapped onto a permission — the same mechanism that already
/// maps a group onto an [AdminRole]. A group that maps to neither grants
/// nothing.
///
/// The wire form is the lowercase `phi:`-prefixed name, not the enum constant,
/// so a configuration file and an audit record read the same.
public enum DataPermission {

	/// That a call exists: metadata, timestamps, partial identifiers. The rung
	/// a queue dashboard or a search result needs, and the one a reviewer holds
	/// before they have a reason to open anything.
	LIST("phi:list"),

	/// Read what was said. Separate from [#PLAY] because a transcript is
	/// searchable, copyable and quotable in a way audio is not, and because a
	/// voice identifies a person even where the words do not.
	TRANSCRIPT("phi:transcript"),

	/// Hear the call, streamed. Deliberately not [#EXPORT]: playing leaves the
	/// content inside the application, where the next access is audited too.
	PLAY("phi:play"),

	/// Take a copy — a file download, or a bulk extract. This is the rung where
	/// content leaves the system's control and stops being auditable, which is
	/// exactly why it is its own permission and not a side effect of [#PLAY].
	EXPORT("phi:export"),

	/// See the fields a classification marks sensitive, rather than the masked
	/// form. Orthogonal to the rungs above: a reviewer may hold [#PLAY] and
	/// still hear a redacted call.
	UNREDACT("phi:unredact"),

	/// Read the access log. Held by the people who audit, and deliberately
	/// **not** by the people being audited — an access log the audited party can
	/// read is a map of what they got away with, and one they can write is not
	/// an access log at all. Reading it is itself an audited act.
	AUDIT("phi:audit"),

	/// Emergency access, for the case where the rules would keep someone out of
	/// a call they genuinely need. It is always granted — that is the point of
	/// it — and it is recorded as its own decision so that using it is visible,
	/// countable, and reviewable after the fact. A break-glass path that is not
	/// louder than an ordinary grant is just a back door.
	BREAKGLASS("phi:breakglass");

	private final String permissionName;

	DataPermission(String permissionName) {
		this.permissionName = permissionName;
	}

	/// The configuration and audit-record form, e.g. `phi:play`.
	public String permissionName() {
		return permissionName;
	}

	/// True if `name` is one of the permission names above.
	public static boolean isDataPermission(String name) {
		return fromName(name) != null;
	}

	/// The [DataPermission] for a permission name, or null if `name` is not
	/// one. Matched case-insensitively, because these are typed by hand into a
	/// configuration file rather than compared against realm group names the
	/// way [AdminRole] is.
	public static DataPermission fromName(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		for (DataPermission permission : values()) {
			if (permission.permissionName.equalsIgnoreCase(trimmed)) {
				return permission;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return permissionName;
	}
}
