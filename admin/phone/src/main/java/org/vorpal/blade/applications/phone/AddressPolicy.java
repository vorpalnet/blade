package org.vorpal.blade.applications.phone;

import java.util.regex.Pattern;

/// Decides which address a signed-in user may be issued a token for.
///
/// Separate from [TokenResource] because it is the rule worth testing, and a
/// JAX-RS resource is an awkward place to test one.
///
/// ## Why choosing an address is allowed at all
///
/// The strictest arrangement — one user, one address, derived from the login —
/// makes the app untestable with a single account: a browser-to-browser call
/// needs two addresses, and most deployments have one `weblogic` operator. It
/// also makes demos awkward, and demos are most of what this app is for.
///
/// So [PhoneSettings#isAllowChosenAddress] permits a caller to name their own,
/// and it defaults to on. What that does **not** do is reopen the hole the token
/// closed:
///
/// - the caller is still authenticated and must still hold a BLADE role — an
///   anonymous client on the network can obtain nothing;
/// - the gateway still honors only the address inside the token, so a browser
///   still cannot claim an address it was not issued;
/// - the token's `sub` is always the real WebLogic user, never the chosen
///   address, so `webrtc` logs who actually registered even when the address
///   they took is someone else's name.
///
/// What is given up is the restriction itself: with this on, an authenticated
/// administrator can be issued a token for any address and receive calls for it.
/// A deployment that wants one address per person turns it off, and every caller
/// gets `<username>@<aorDomain>` and nothing else.
public final class AddressPolicy {

	/// `user@host`, which is the exact form `InboundToBrowser.addressOf` derives
	/// from a request URI. An address in any other shape could never receive a
	/// call, so accepting one would only produce a registration that silently
	/// never rings.
	private static final Pattern ADDRESS = Pattern.compile(
			"^[A-Za-z0-9._%+!~*'()-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})*$");

	private AddressPolicy() {
	}

	/// An address was asked for and will not be issued.
	public static final class AddressRejected extends Exception {
		private static final long serialVersionUID = 1L;

		private final int status;

		AddressRejected(int status, String message) {
			super(message);
			this.status = status;
		}

		/// The HTTP status this maps to: 400 when the request itself is wrong,
		/// 403 when it is well formed and refused.
		public int getStatus() {
			return status;
		}
	}

	/// The address this user gets when they ask for nothing in particular.
	public static String defaultAddress(String username, PhoneSettings settings) {
		return username + "@" + settings.getAorDomain();
	}

	/// Resolve the address to put in the token.
	///
	/// @param username  the authenticated principal
	/// @param requested what the caller asked for, or null/blank for the default
	public static String resolve(String username, String requested, PhoneSettings settings)
			throws AddressRejected {
		if (requested == null || requested.trim().isEmpty()) {
			return defaultAddress(username, settings);
		}
		String address = requested.trim();

		if (address.equals(defaultAddress(username, settings))) {
			// Asking for exactly what you would have been given anyway is always
			// fine, so a page that echoes the address back does not need to know
			// whether choosing is permitted.
			return address;
		}
		if (!settings.isAllowChosenAddress()) {
			throw new AddressRejected(403, "This deployment issues one address per user. "
					+ "'" + username + "' is " + defaultAddress(username, settings)
					+ ". Set allowChosenAddress in blade-phone.json to allow another.");
		}
		if (!ADDRESS.matcher(address).matches()) {
			throw new AddressRejected(400, "'" + address + "' is not a usable address. "
					+ "It must be user@host — the form an inbound INVITE's request URI resolves to.");
		}
		return address;
	}
}
