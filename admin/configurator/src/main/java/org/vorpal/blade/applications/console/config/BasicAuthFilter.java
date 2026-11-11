package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

/// HTTP Basic authentication for the Configurator REST API (`/api/v1/*`).
///
/// The browser app keeps FORM login. The `/api/v1/*` paths are carved out of
/// the FORM security-constraint in `web.xml` (no container auth) and
/// authenticated here instead, so command-line clients such as
/// `blade-validate.sh` can pass credentials in the `Authorization: Basic`
/// header rather than driving `j_security_check`.
///
/// Credentials are validated against the same WebLogic realm FORM uses, via
/// [HttpServletRequest#login], then the authenticated user must hold one of
/// the admin roles the FORM constraint required. This filter applies to every
/// JAX-RS resource in the app because they are all rooted at `/api/v1` (see
/// [RestApplication]).
@Provider
public class BasicAuthFilter implements ContainerRequestFilter {

	private static final Logger logger = Logger.getLogger(BasicAuthFilter.class.getName());
	private static final String REALM = "BLADE Configurator";
	private static final String BASIC_PREFIX = "Basic ";
	private static final String[] ADMIN_ROLES = { "Admin", "Operator", "Deployer", "Monitor" };

	@Context
	private HttpServletRequest request;

	@Override
	public void filter(ContainerRequestContext ctx) throws IOException {
		String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
			challenge(ctx, "Missing Basic credentials");
			return;
		}

		String user;
		String password;
		try {
			String decoded = new String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length()).trim()),
					StandardCharsets.UTF_8);
			int colon = decoded.indexOf(':');
			if (colon < 0) {
				challenge(ctx, "Malformed Basic credentials");
				return;
			}
			user = decoded.substring(0, colon);
			password = decoded.substring(colon + 1);
		} catch (IllegalArgumentException e) {
			challenge(ctx, "Malformed Basic credentials");
			return;
		}

		try {
			// A client keeping cookies arrives logged in from its last call, and
			// login() then throws (a false "Invalid credentials").
			if (request.getUserPrincipal() != null) request.logout();
			request.login(user, password);
		} catch (ServletException e) {
			logger.log(Level.FINE, "Basic auth failed for user " + user, e);
			challenge(ctx, "Invalid credentials");
			return;
		}

		// Reading (GET: validate) is open to every admin role; anything that
		// changes the domain or spends its API key (POST: deploy, publish,
		// ai/generate) needs Admin or Operator, as on the WebSocket.
		String method = ctx.getMethod();
		boolean reads = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
		if (!reads) {
			if (request.isUserInRole("Admin") || request.isUserInRole("Operator")) {
				return;
			}
			ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
					.entity("{\"error\":\"" + method + " needs the Admin or Operator role\"}")
					.type(MediaType.APPLICATION_JSON).build());
			return;
		}
		for (String role : ADMIN_ROLES) {
			if (request.isUserInRole(role)) {
				return; // authenticated and authorized
			}
		}

		ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
				.entity(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
						.put("error", "User '" + user + "' lacks a required admin role").toString())
				.type(MediaType.APPLICATION_JSON).build());
	}

	/// Abort with 401 and a `WWW-Authenticate` header so clients know to send
	/// HTTP Basic credentials.
	private void challenge(ContainerRequestContext ctx, String message) {
		ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"")
				.entity("{\"error\":\"" + message + "\"}")
				.type(MediaType.APPLICATION_JSON).build());
	}
}
