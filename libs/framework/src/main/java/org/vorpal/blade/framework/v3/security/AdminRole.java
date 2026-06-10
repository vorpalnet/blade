package org.vorpal.blade.framework.v3.security;

/// The four BLADE admin roles, externally defined in the WebLogic realm and
/// referenced by every admin app's `web.xml` `<auth-constraint>` and
/// `weblogic.xml` `<security-role-assignment>`.
///
/// These are the *only* role names BLADE grants administrative access to.
/// Both the container FORM/BASIC path ([org.vorpal.blade.applications.console.config.BasicAuthFilter])
/// and the inbound-JWT path ([JwtAuthFilter]) authorize against this set, so
/// the two front doors stay consistent.
public enum AdminRole {
	ADMIN("Admin"),
	OPERATOR("Operator"),
	DEPLOYER("Deployer"),
	MONITOR("Monitor");

	private final String roleName;

	AdminRole(String roleName) {
		this.roleName = roleName;
	}

	/// The realm/`web.xml` role name (e.g. `Admin`), not the enum constant.
	public String roleName() {
		return roleName;
	}

	/// True if `name` is one of the four admin role names (case-sensitive,
	/// matching how WebLogic compares realm group names).
	public static boolean isAdminRole(String name) {
		return fromName(name) != null;
	}

	/// The [AdminRole] for a role name, or null if `name` is not an admin role.
	public static AdminRole fromName(String name) {
		if (name == null) {
			return null;
		}
		for (AdminRole role : values()) {
			if (role.roleName.equals(name)) {
				return role;
			}
		}
		return null;
	}
}
