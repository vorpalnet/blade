package org.vorpal.blade.applications.console.security;

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
