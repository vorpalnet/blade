package org.vorpal.blade.framework.v3.security;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;

/// Inbound bearer-JWT authentication for admin JAX-RS resources, modeled on
/// [org.vorpal.blade.applications.console.config.BasicAuthFilter].
///
/// **Additive by design.** It only ever acts on a request that carries an
/// `Authorization: Bearer <jwt>` header *and* only when JWT auth is enabled in
/// config. In every other case it does nothing and lets the container FORM
/// (browser) / BASIC (CLI) login handle the request — so dropping this filter
/// into an app never breaks the existing login or the `BLADEADMINSESSION` SSO
/// cookie. Turning JWT on or off is a config flag, not a redeploy.
///
/// **Where the config comes from.** This filter lives in the framework jar so
/// any admin app can register it, but it must not depend on a particular app's
/// settings type. So it reads a `Supplier<JwtAuthConfig>` published into the
/// [ServletContext] under [#CONFIG_SUPPLIER_ATTR] by the owning app's startup
/// listener (see the `security` admin app). The supplier returns the *live*
/// config each call, so Configurator edits take effect without a redeploy; the
/// built [JwtValidator] is cached and only rebuilt when the config instance
/// changes.
///
/// **Registration.** Because the filter ships in the shared framework jar
/// (never bundled in a skinny WAR), JAX-RS classpath scanning won't auto-find
/// it — an app opts in by returning it from its `Application#getClasses()`.
///
/// Cross-WAR note: today the supplier is published by the app that owns the
/// security settings. Distributing one security config to *every* admin WAR
/// (so JWT can guard all of them, not just its host) is the next refinement —
/// see SECURITY.md.
@Provider
public class JwtAuthFilter implements ContainerRequestFilter {

	/// ServletContext attribute under which the owning app publishes a
	/// `Supplier<JwtAuthConfig>` returning the current config.
	public static final String CONFIG_SUPPLIER_ATTR =
			"org.vorpal.blade.framework.v3.security.JwtAuthConfig.supplier";

	private static final String BEARER_PREFIX = "Bearer ";
	private static final Logger logger = Logger.getLogger(JwtAuthFilter.class.getName());

	@Context
	private ServletContext servletContext;

	private volatile JwtAuthConfig boundConfig;
	private volatile JwtValidator boundValidator;

	@Override
	public void filter(ContainerRequestContext ctx) {
		JwtAuthConfig config = currentConfig();
		if (config == null || !config.isEnabled()) {
			return; // JWT off → container FORM/BASIC handles this request
		}

		String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return; // no bearer token → defer to FORM/BASIC; nothing to assert
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();

		JwtValidator validator;
		try {
			validator = validatorFor(config);
		} catch (JwtAuthException e) {
			// Enabled but unusable (e.g. bad jwksUri). Fail the bearer request
			// closed rather than silently fall through to FORM.
			logger.log(Level.WARNING, "JWT auth enabled but misconfigured: " + e.getMessage(), e);
			challenge(ctx, "JWT authentication unavailable");
			return;
		}

		try {
			JwtIdentity identity = validator.validate(token);
			if (!identity.hasAnyAdminRole()) {
				ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
						.entity("{\"error\":\"Token for '" + identity.getName()
								+ "' carries no BLADE admin role\"}")
						.type(MediaType.APPLICATION_JSON).build());
				return;
			}
			SecurityContext existing = ctx.getSecurityContext();
			boolean secure = (existing != null) && existing.isSecure();
			ctx.setSecurityContext(new JwtSecurityContext(identity, secure));
		} catch (JwtAuthException e) {
			logger.log(Level.FINE, "bearer token rejected", e);
			challenge(ctx, "Invalid bearer token");
		}
	}

	/// Read the live config from the app-published supplier. Returns null when
	/// no supplier is present (the filter then no-ops).
	private JwtAuthConfig currentConfig() {
		if (servletContext == null) {
			return null;
		}
		Object attr = servletContext.getAttribute(CONFIG_SUPPLIER_ATTR);
		if (!(attr instanceof Supplier)) {
			return null;
		}
		try {
			Object cfg = ((Supplier<?>) attr).get();
			return (cfg instanceof JwtAuthConfig) ? (JwtAuthConfig) cfg : null;
		} catch (RuntimeException e) {
			logger.log(Level.FINE, "JWT config supplier failed", e);
			return null;
		}
	}

	/// Cache a validator per config instance; rebuild only when the config
	/// object changes (i.e. on a Configurator edit / reload).
	private JwtValidator validatorFor(JwtAuthConfig config) throws JwtAuthException {
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

	private void challenge(ContainerRequestContext ctx, String message) {
		ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.entity("{\"error\":\"" + message + "\"}")
				.type(MediaType.APPLICATION_JSON).build());
	}
}
