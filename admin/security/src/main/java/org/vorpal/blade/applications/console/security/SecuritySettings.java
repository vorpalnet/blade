package org.vorpal.blade.applications.console.security;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.security.JwtAuthConfig;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Security admin app — the single place an operator
/// configures BLADE's inbound authentication posture, edited through the
/// Configurator like every other BLADE app's config.
///
/// Today it holds the inbound bearer-JWT settings ([JwtAuthConfig]) used for
/// enterprise-IdP SSO on the admin tier. The container FORM/BASIC login and the
/// configurable SIP trust model are documented in `SECURITY.md`; the SIP side
/// is deployment-descriptor driven and has no knob here by design.
@SchemaAbout(
		name = "Security",
		tagline = "Authentication & Identity",
		description = "Configure how callers authenticate to BLADE. Inbound bearer-JWT single sign-on against your enterprise identity provider for the admin consoles (additive to the FORM/BASIC login). See SECURITY.md for the full picture: container realm roles, the configurable SIP trust model, credential storage, and TLS/mTLS.")
public class SecuritySettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private JwtAuthConfig jwt = new JwtAuthConfig();

	@JsonPropertyDescription("Inbound bearer-JWT authentication for admin requests (enterprise IdP single sign-on). Additive to the container FORM/BASIC login and disabled by default.")
	public JwtAuthConfig getJwt() {
		return jwt;
	}

	public void setJwt(JwtAuthConfig jwt) {
		this.jwt = (jwt == null) ? new JwtAuthConfig() : jwt;
	}
}
