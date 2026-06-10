package org.vorpal.blade.applications.console.security;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/security.json` on first deployment when no
/// operator-supplied file is present. JWT auth starts disabled, so the admin
/// tier keeps its FORM/BASIC login until an operator fills in the IdP fields
/// and flips `jwt.enabled`.
public class SecuritySettingsSample extends SecuritySettings {
	private static final long serialVersionUID = 1L;

	public SecuritySettingsSample() {
		this.about.setName("Security")
				.setTagline("Authentication & Identity")
				.setDescription("Configure how callers authenticate to BLADE. Inbound bearer-JWT single sign-on against your enterprise identity provider for the admin consoles (additive to the FORM/BASIC login). See SECURITY.md for the full picture: container realm roles, the configurable SIP trust model, credential storage, and TLS/mTLS.");
	}
}
