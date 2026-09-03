package org.vorpal.blade.applications.console.security;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.JwtAuthConfig;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Security admin app — the single place an operator
/// configures BLADE's inbound authentication posture, edited through the
/// Configurator like every other BLADE app's config.
///
/// It holds two things, and they are deliberately separate.
///
/// [JwtAuthConfig] is about **admission**: who may into the admin tier at all,
/// and it answers in the four platform roles. [AccessPolicy] is about
/// **content**: who may hear a call, read a transcript, or take a copy, and it
/// answers in `phi:` permissions that no platform role grants. Keeping them in
/// one settings object puts the whole picture on one Configurator form; keeping
/// them as two objects is what stops a `Monitor` inheriting a patient's audio.
///
/// The container FORM/BASIC login and the configurable SIP trust model are
/// documented in `SECURITY.md`; the SIP side is deployment-descriptor driven
/// and has no knob here by design. On OCCAS 8.3 the browser single sign-on is
/// domain configuration rather than a field here — see `IAM.md`.
@SchemaAbout(
		name = "Security",
		tagline = "Authentication & Identity",
		description = "Configure how callers authenticate to BLADE. Inbound bearer-JWT single sign-on against your enterprise identity provider for the admin consoles (additive to the FORM/BASIC login). See SECURITY.md for the full picture: container realm roles, the configurable SIP trust model, credential storage, and TLS/mTLS.")
public class SecuritySettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private JwtAuthConfig jwt = new JwtAuthConfig();
	private AccessPolicy access = new AccessPolicy();

	@JsonPropertyDescription("Inbound bearer-JWT authentication for admin requests (enterprise IdP single sign-on). Additive to the container FORM/BASIC login and disabled by default.")
	public JwtAuthConfig getJwt() {
		return jwt;
	}

	public void setJwt(JwtAuthConfig jwt) {
		this.jwt = (jwt == null) ? new JwtAuthConfig() : jwt;
	}

	@JsonPropertyDescription("Who may see, hear, export or unredact call content. An ordered list of rules; the first that is about this caller, about this record, and grants the permission asked for decides. There is no 'allow' default: an empty list refuses everything, which is what an unconfigured deployment should do.")
	public AccessPolicy getAccess() {
		return access;
	}

	public void setAccess(AccessPolicy access) {
		this.access = (access == null) ? new AccessPolicy() : access;
	}
}
