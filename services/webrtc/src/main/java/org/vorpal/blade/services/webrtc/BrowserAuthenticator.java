package org.vorpal.blade.services.webrtc;

import org.vorpal.blade.framework.v3.security.JwtAuthConfig;
import org.vorpal.blade.framework.v3.security.JwtAuthException;
import org.vorpal.blade.framework.v3.security.JwtIdentity;
import org.vorpal.blade.framework.v3.security.JwtValidator;

/// Decides whether a browser may claim an address on this gateway.
///
/// Deliberately a plain object with no container in it — no WebSocket session,
/// no servlet, no SIP. [SignalEndpoint] hands it a token and an address and acts
/// on the answer, which is what lets the rule that actually protects the service
/// be tested without a running OCCAS.
///
/// ## The address comes from the token, not from the request
///
/// Authentication and authorization are separate questions here and the second
/// one is the one that matters. Checking only the signature would leave every
/// signed-in employee able to register as any colleague and take their calls —
/// a hijack performed by a fully authenticated user. So the token names the one
/// address its holder may bind, and a browser asking for a different one is
/// refused rather than quietly corrected: at that point either the deployment is
/// misconfigured or someone is trying it on, and both deserve to be visible.
public class BrowserAuthenticator {

	/// The answer, and enough context to log it or tell the browser why.
	public static final class Decision {

		private final boolean allowed;
		private final boolean authenticated;
		private final String aor;
		private final String user;
		private final String reason;
		private final java.util.List<String> roles;

		private Decision(boolean allowed, boolean authenticated, String aor, String user, String reason,
				java.util.List<String> roles) {
			this.allowed = allowed;
			this.authenticated = authenticated;
			this.aor = aor;
			this.user = user;
			this.reason = reason;
			this.roles = roles;
		}

		static Decision allow(String aor, String user, java.util.List<String> roles) {
			return new Decision(true, true, aor, user, null, roles);
		}

		/// Allowed, but nothing was proved — authentication is switched off.
		static Decision unauthenticated(String aor) {
			return new Decision(true, false, aor, null, null, java.util.Collections.emptyList());
		}

		static Decision deny(String reason) {
			return new Decision(false, false, null, null, reason, java.util.Collections.emptyList());
		}

		public boolean isAllowed() {
			return allowed;
		}

		/// True only when a valid token established who this is. False when the
		/// deployment has authentication turned off — the browser is let in, but
		/// the gateway knows it is taking the address on trust, and says so in
		/// `session.ready` so the page can show it.
		public boolean isAuthenticated() {
			return authenticated;
		}

		/// The address the browser may bind: the token's claim when there is one,
		/// the requested address when authentication is off.
		public String getAor() {
			return aor;
		}

		/// The authenticated principal, or null when unauthenticated.
		public String getUser() {
			return user;
		}

		/// Why the browser was refused, in words worth sending back. Null when allowed.
		public String getReason() {
			return reason;
		}

		/// Every role and group the token's roles claim carried, mapped or as written. Empty when
		/// unauthenticated. The gateway asserts them on the calls this browser places, so the
		/// application behind it can decide by role (who hosts a meeting) without a login of its own.
		public java.util.List<String> getRoles() {
			return roles;
		}
	}

	private volatile JwtAuthConfig boundConfig;
	private volatile JwtValidator boundValidator;

	/// Decide whether `requestedAor` may be claimed with `token`.
	///
	/// @param config       the live JWT settings, or null if the app has none
	/// @param token        the token the browser sent, or null if it sent none
	/// @param requestedAor the address the browser asked for, or null to accept
	///                     whatever the token grants
	public Decision authorize(JwtAuthConfig config, String token, String requestedAor) {
		return authorize(config, java.util.Collections.emptyList(), token, requestedAor);
	}

	/// Decide whether `requestedAor` may be claimed with `token`, admitting holders of
	/// `browserRoles` as well as of the admin roles.
	///
	/// @param browserRoles roles or groups, beyond the four admin roles, whose members may connect
	public Decision authorize(JwtAuthConfig config, java.util.List<String> browserRoles, String token,
			String requestedAor) {
		if (config == null) {
			// No configuration at all — the settings failed to load, or the
			// endpoint started before the SIP servlet that owns them. Refuse.
			// "We could not read the rule" must never mean "there is no rule":
			// that is how a service ends up open because of an unrelated fault.
			return Decision.deny("this gateway has no configuration loaded yet; try again shortly");
		}
		if (!config.isEnabled()) {
			// Open mode — an operator turned authentication off. Deliberate, and
			// logged at SEVERE on startup; see WebrtcSettings.
			if (requestedAor == null || requestedAor.trim().isEmpty()) {
				return Decision.deny("session.connect requires an aor");
			}
			return Decision.unauthenticated(requestedAor.trim());
		}

		if (token == null || token.trim().isEmpty()) {
			return Decision.deny("this gateway requires an authentication token; "
					+ "obtain one from the phone app and send it in session.connect");
		}

		JwtValidator validator;
		try {
			validator = validatorFor(config);
		} catch (JwtAuthException e) {
			// Enabled but unusable — most often a jwksUri that is blank or points
			// somewhere unreachable. Fail closed, and name the setting.
			return Decision.deny("browser authentication is misconfigured on this gateway "
					+ "(jwksUri): " + e.getMessage());
		}

		JwtIdentity identity;
		try {
			identity = validator.validate(token);
		} catch (JwtAuthException e) {
			return Decision.deny("token rejected: " + e.getMessage());
		}

		java.util.List<String> held = new java.util.ArrayList<>(identity.roles());
		for (String group : identity.groups()) {
			if (!held.contains(group)) {
				held.add(group);
			}
		}
		boolean admitted = identity.hasAnyAdminRole();
		if (!admitted && browserRoles != null) {
			for (String role : browserRoles) {
				admitted |= held.contains(role);
			}
		}
		if (!admitted) {
			return Decision.deny("'" + identity.getName() + "' carries no role this gateway admits");
		}

		String granted = identity.claim("aor");
		if (granted == null || granted.trim().isEmpty()) {
			return Decision.deny("token for '" + identity.getName() + "' names no address (aor claim)");
		}
		granted = granted.trim();

		if (requestedAor != null && !requestedAor.trim().isEmpty()
				&& !granted.equals(requestedAor.trim())) {
			return Decision.deny("token for '" + identity.getName() + "' grants " + granted
					+ ", not " + requestedAor.trim());
		}

		return Decision.allow(granted, identity.getName(), held);
	}

	/// The validator for `config`, cached until the config object changes — the
	/// same arrangement `JwtAuthFilter` uses, for the same reason: building one
	/// opens a JWKS source, which is not per-message work.
	///
	/// Overridable so a test can supply keys in memory instead of over HTTP.
	protected JwtValidator validatorFor(JwtAuthConfig config) throws JwtAuthException {
		JwtValidator current = boundValidator;
		if (current != null && boundConfig == config) {
			return current;
		}
		synchronized (this) {
			if (boundValidator != null && boundConfig == config) {
				return boundValidator;
			}
			JwtValidator built = JwtValidator.forConfig(config);
			boundValidator = built;
			boundConfig = config;
			return built;
		}
	}
}
