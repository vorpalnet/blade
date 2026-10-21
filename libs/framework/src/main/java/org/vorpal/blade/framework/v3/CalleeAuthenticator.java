package org.vorpal.blade.framework.v3;

import javax.servlet.sip.AuthInfo;
import javax.servlet.sip.SipServletResponse;

/// A B2BUA listener that answers the callee's digest challenge itself.
///
/// A carrier that authenticates outbound calls by digest answers the first INVITE with a
/// 401 or 407. The B2BUA relays any failure to the caller, so without this the caller sees
/// the challenge and the call ends. A listener that implements this interface is asked for
/// credentials first; the B2BUA resends the INVITE with them and the caller sees only the
/// outcome.
@FunctionalInterface
public interface CalleeAuthenticator {

	/// The credentials to answer `challenge` with, built with `SipFactory.createAuthInfo()`
	/// for the realms in `challenge.getChallengeRealms()`. Return null to relay the
	/// challenge to the caller instead.
	///
	/// @param challenge a 401 or 407 response to the callee's INVITE
	/// @return the credentials, or null
	AuthInfo credentials(SipServletResponse challenge);
}
