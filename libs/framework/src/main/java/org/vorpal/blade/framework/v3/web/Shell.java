package org.vorpal.blade.framework.v3.web;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.security.JwtIdentity;
import org.vorpal.blade.framework.v3.security.OidcLoginFilter;

/// What an admin page's frame says about the person and the application: the
/// account menu's name, how they signed in and where "Sign out" goes, and the
/// About panel's build. The portal's `brand/shell.js` reads it from
/// [ShellServlet] in every application, so each answers for its own sign-in.
public final class Shell {

	/// The portal's sign-out of every application on the AdminServer.
	public static final String SIGN_OUT_EVERYWHERE = "/blade/portal/logout";

	private Shell() {
	}

	/// The person's name as the identity domain gives it, else the user name.
	public static String displayName(Principal principal, String remoteUser) {
		if (principal instanceof JwtIdentity) {
			JwtIdentity id = (JwtIdentity) principal;
			for (String claim : new String[] { "user_displayname", "name", "preferred_username" }) {
				String v = id.claim(claim);
				if (v != null && !v.isBlank()) {
					return v;
				}
			}
		}
		return remoteUser;
	}

	/// "identity domain" or "WebLogic", as the account menu words it.
	public static String signedInWith(String authType) {
		return OidcLoginFilter.AUTH_TYPE.equals(authType) ? "identity domain" : "WebLogic";
	}

	/// Where "Sign out" goes. On the AdminServer, the portal's sign-out of
	/// every application there, by way of the identity domain for a sign-in it
	/// made (only this application holds that sign-in's ID token). On another
	/// server there is no portal: an identity-domain sign-in ends here and at
	/// the identity domain; a WebLogic one has no sign-out page (null).
	public static String signOut(String authType, String contextPath, boolean onAdminServer) {
		if (OidcLoginFilter.AUTH_TYPE.equals(authType)) {
			return contextPath + OidcLoginFilter.LOGOUT_PATH
					+ (onAdminServer ? "?" + OidcLoginFilter.THEN_PARAM + "=" + SIGN_OUT_EVERYWHERE : "");
		}
		return onAdminServer ? SIGN_OUT_EVERYWHERE : null;
	}

	/// The build facts from a WAR's manifest attributes, for About.
	public static Map<String, Object> build(java.util.jar.Attributes a) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (a != null) {
			out.put("version", a.getValue("Implementation-Version"));
			out.put("built", a.getValue("Implementation-Build-Date"));
			out.put("mode", a.getValue("Blade-Build-Mode"));
			out.put("copyright", a.getValue("Copyright"));
			out.put("license", a.getValue("License"));
		}
		return out;
	}
}
