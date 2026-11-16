package org.vorpal.blade.applications.callflow;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.vorpal.blade.framework.v3.access.Access;

/// Who may change what Trace records and read the applications' source: a
/// person in one of the WebLogic admin roles ([Access#ADMIN_ROLES]).
///
/// Trace declares no `auth-constraint`, so that an identity-domain sign-in can
/// start (the container checks a constraint before any filter runs). Anyone
/// signed in therefore reaches every endpoint, and grants only narrow what
/// they read. Arming a rule decides whose calls are captured on every server,
/// and the source is the deployed code of every application, a customer's
/// included: neither is a tenant's business, so each such endpoint asks here
/// first. A Monitor passes this test and is still refused a change by the
/// framework's `MonitorReadOnlyFilter`.
final class Operators {

	private Operators() {
	}

	/// True if the caller holds a WebLogic admin role.
	static boolean is(SecurityContext security) {
		if (security == null) {
			return false;
		}
		for (String role : Access.ADMIN_ROLES) {
			if (security.isUserInRole(role)) {
				return true;
			}
		}
		return false;
	}

	/// The `403` for a caller who is not, naming what they tried.
	static Response refused(String what) {
		return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN)
				.entity(what + " needs a WebLogic admin role: Admin, Operator, Deployer or Monitor").build();
	}
}
