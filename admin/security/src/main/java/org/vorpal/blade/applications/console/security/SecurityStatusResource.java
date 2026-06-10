package org.vorpal.blade.applications.console.security;

import java.security.Principal;
import java.util.function.Supplier;

import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.vorpal.blade.framework.v3.security.JwtAuthConfig;
import org.vorpal.blade.framework.v3.security.JwtAuthFilter;

/// Read-only status for the Security admin app, at
/// `GET /blade/security/api/v1/status`.
///
/// Reports who the caller is (works for both FORM/BASIC and bearer-JWT logins,
/// since [JwtAuthFilter] installs a SecurityContext) and whether JWT auth is
/// currently enabled — a quick "is SSO wired up correctly?" check, analogous to
/// the configuration self-checks other admin apps expose. Guarded by the
/// app's FORM constraint, so reaching it at all already proves an admin login.
@Path("/status")
public class SecurityStatusResource {

	@Context
	private ServletContext servletContext;

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response status(@Context SecurityContext sec) {
		Principal principal = (sec == null) ? null : sec.getUserPrincipal();
		String user = (principal == null) ? null : principal.getName();
		String scheme = (sec == null) ? null : sec.getAuthenticationScheme();
		JwtAuthConfig jwt = currentJwtConfig();

		StringBuilder json = new StringBuilder(160);
		json.append('{');
		json.append("\"user\":").append(quote(user)).append(',');
		json.append("\"authScheme\":").append(quote(scheme)).append(',');
		json.append("\"jwtEnabled\":").append(jwt != null && jwt.isEnabled()).append(',');
		json.append("\"jwtIssuer\":").append(quote(jwt == null ? null : jwt.getIssuer()));
		json.append('}');
		return Response.ok(json.toString(), MediaType.APPLICATION_JSON).build();
	}

	private JwtAuthConfig currentJwtConfig() {
		Object attr = (servletContext == null) ? null
				: servletContext.getAttribute(JwtAuthFilter.CONFIG_SUPPLIER_ATTR);
		if (!(attr instanceof Supplier)) {
			return null;
		}
		Object cfg = ((Supplier<?>) attr).get();
		return (cfg instanceof JwtAuthConfig) ? (JwtAuthConfig) cfg : null;
	}

	/// Minimal JSON string quoting — escapes the characters that can occur in a
	/// username or issuer URL. Output here is small and controlled, so this
	/// avoids pulling a JSON provider into the resource.
	private static String quote(String value) {
		if (value == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				sb.append(c);
				break;
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
