package org.vorpal.blade.applications.console.security;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/security.json` on first deployment when no
/// operator-supplied file is present. JWT auth starts disabled, so the admin
/// tier keeps its FORM/BASIC login until an operator fills in the IdP fields
/// and flips `jwt.enabled`.
public class SecuritySettingsSample extends SecuritySettings {
	private static final long serialVersionUID = 1L;

	public SecuritySettingsSample() {
	}
}
