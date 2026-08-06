package org.vorpal.blade.applications.phone;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.vorpal.blade.framework.v3.security.AdminRole;
import org.vorpal.blade.framework.v3.security.JwtAuthException;
import org.vorpal.blade.framework.v3.security.JwtIssuer;

/// The phone's two token endpoints — the whole server side of browser
/// authentication for the WebRTC gateway.
///
/// ## Why the page needs a token at all
///
/// The user is already authenticated when they load this page: the container's
/// FORM login checked them against the WebLogic realm, and `BLADEADMINSESSION`
/// carries that fact across the admin tier. None of it reaches the gateway. The
/// `webrtc` service runs on the engine tier — a different host, a different
/// port — so the admin session cookie is not sent to it, and the browser
/// WebSocket API cannot attach an `Authorization` header to the handshake even
/// if there were something to attach.
///
/// So the proof has to travel inside the protocol, as data the browser hands
/// over and cannot tamper with. That is a signed token, and this is where it is
/// minted.
///
/// ## Why the browser does not choose its own address
///
/// [#token] derives the address-of-record from the authenticated username and
/// writes it into the token as a claim. The browser is told what it got; it
/// never asks. A user signed in as `alice` receives a token good for
/// `alice@<aorDomain>` and for nothing else, because the gateway honors the
/// claim rather than the address the browser later names.
///
/// Skipping that step would leave the hole half-closed: every caller would be a
/// signed-in employee, and any one of them could still register as the CEO and
/// take their calls. Authentication says who is asking; only the claim says what
/// they may have.
@Path("/")
public class TokenResource {

	private static final Logger log = Logger.getLogger(TokenResource.class.getName());

	@Context
	private SecurityContext security;

	/// Who the caller is and what this deployment will let them do — read by the
	/// page on load, so the address field arrives filled in and either editable
	/// or not, instead of only finding out when Register is pressed.
	@GET
	@Path("session")
	@Produces(MediaType.APPLICATION_JSON)
	public Response session() {
		String username = authenticatedUser();
		if (username == null) {
			return error(Response.Status.UNAUTHORIZED, "Not signed in.");
		}
		PhoneSettings settings = PhoneStartupListener.current();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("user", username);
		body.put("aor", AddressPolicy.defaultAddress(username, settings));
		body.put("allowChosenAddress", settings.isAllowChosenAddress());
		body.put("gateway", settings.getGateway());
		body.put("stunServer", settings.getStunServer());
		return Response.ok(body).header("Cache-Control", "no-store").build();
	}

	/// Mint a token for the signed-in user.
	///
	/// Reached only through the container's FORM constraint (see `web.xml`), so
	/// by the time this runs the caller is authenticated. The role check below
	/// is not a second door — it puts the caller's roles *into* the token, and
	/// refuses to mint one that would grant nothing.
	///
	/// @param requestedAor the address to be issued for, or blank for this
	///                     user's default. Whether another may be asked for is
	///                     [AddressPolicy]'s decision, not this method's.
	@POST
	@Path("token")
	@Produces(MediaType.APPLICATION_JSON)
	public Response token(@QueryParam("aor") String requestedAor) {
		JwtIssuer issuer = PhoneStartupListener.issuer;
		if (issuer == null) {
			// Built at startup; absent means the app failed to initialize. Say so
			// rather than 500, because the fix is in the log, not in the request.
			return error(Response.Status.SERVICE_UNAVAILABLE,
					"The phone's token issuer did not start. Check the server log for the failure at deployment.");
		}

		String username = authenticatedUser();
		if (username == null) {
			// Only reachable if the security constraint is removed from web.xml.
			return error(Response.Status.UNAUTHORIZED, "Not signed in.");
		}

		List<String> roles = new ArrayList<>();
		for (AdminRole role : AdminRole.values()) {
			if (security.isUserInRole(role.roleName())) {
				roles.add(role.roleName());
			}
		}
		if (roles.isEmpty()) {
			return error(Response.Status.FORBIDDEN,
					"'" + username + "' holds no BLADE admin role, so there is nothing to put in a token.");
		}

		PhoneSettings settings = PhoneStartupListener.current();
		String aor;
		try {
			aor = AddressPolicy.resolve(username, requestedAor, settings);
		} catch (AddressPolicy.AddressRejected e) {
			return error(Response.Status.fromStatusCode(e.getStatus()), e.getMessage());
		}

		Map<String, String> claims = new LinkedHashMap<>();
		claims.put("aor", aor);

		try {
			// The subject is the real signed-in user, never the address. When the
			// two differ — someone testing with two tabs on one account — the
			// gateway's log still records who actually registered.
			String token = issuer.mint(username, roles, claims);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("token", token);
			body.put("aor", aor);
			body.put("user", username);
			body.put("expiresIn", issuer.ttlSeconds());
			body.put("gateway", settings.getGateway());
			body.put("stunServer", settings.getStunServer());
			return Response.ok(body)
					// A bearer token in a shared cache would be a bearer token for
					// whoever reads the cache.
					.header("Cache-Control", "no-store")
					.build();
		} catch (JwtAuthException e) {
			log.log(Level.WARNING, "could not mint a phone token for '" + username + "'", e);
			return error(Response.Status.SERVICE_UNAVAILABLE, "Could not mint a token: " + e.getMessage());
		}
	}

	/// The public signing keys, in the standard JWKS form the engine tier fetches.
	///
	/// Deliberately unauthenticated — `web.xml` carves this one path out of the
	/// FORM constraint. It has to be: the gateway fetches it from another host
	/// with no admin session, and it is how that gateway learns to distrust
	/// everything this issuer did not sign. There is nothing secret in it; the
	/// private half never leaves the JVM.
	@GET
	@Path("jwks.json")
	@Produces(MediaType.APPLICATION_JSON)
	public Response jwks() {
		JwtIssuer issuer = PhoneStartupListener.issuer;
		if (issuer == null) {
			return error(Response.Status.SERVICE_UNAVAILABLE, "No token issuer is running.");
		}
		// Already a serialized JWKS document; hand it over as-is rather than
		// round-tripping it through a JSON binding that might reorder it.
		return Response.ok(issuer.jwksJson(), MediaType.APPLICATION_JSON).build();
	}

	/// The signed-in principal, or null when there is none.
	private String authenticatedUser() {
		Principal principal = (security == null) ? null : security.getUserPrincipal();
		if (principal == null || principal.getName() == null || principal.getName().trim().isEmpty()) {
			return null;
		}
		return principal.getName().trim();
	}

	private static Response error(Response.Status status, String message) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("error", message);
		return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
	}
}
