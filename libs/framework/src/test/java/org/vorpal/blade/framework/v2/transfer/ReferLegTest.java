package org.vorpal.blade.framework.v2.transfer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;

/// Only the callee (contact-center) leg may start a transfer; a caller's REFER
/// can name any destination.
class ReferLegTest {

	private static DetachedRequest referOn(DetachedSipSession leg) throws Exception {
		DetachedRequest refer = new DetachedRequest((DetachedApplicationSession) leg.getApplicationSession(), "REFER");
		refer.setSession(leg);
		refer.setHeader("Refer-To", "<sip:011442079460000@carrier.example.com>");
		return refer;
	}

	private static DetachedSipSession leg() {
		return new DetachedSipSession(new DetachedApplicationSession("transfer"));
	}

	@Test
	void calleeLegPlacedByTheInitialInviteMayTransfer() throws Exception {
		DetachedSipSession callee = leg();
		callee.setAttribute(InitialInvite.ATTR_INITIAL_INVITE, "placed");
		assertTrue(Transfer.isFromCalleeLeg(referOn(callee)));
	}

	@Test
	void callerLegMayNot() throws Exception {
		assertFalse(Transfer.isFromCalleeLeg(referOn(leg())));
	}

	@Test
	void afterATransferTheRolesFollowTheUserAgentMark() throws Exception {
		DetachedSipSession newCallee = leg();
		newCallee.setAttribute("userAgent", "callee");
		assertTrue(Transfer.isFromCalleeLeg(referOn(newCallee)));

		DetachedSipSession oldCaller = leg();
		oldCaller.setAttribute("userAgent", "caller");
		oldCaller.setAttribute(InitialInvite.ATTR_INITIAL_INVITE, "stale");
		assertFalse(Transfer.isFromCalleeLeg(referOn(oldCaller)));
	}
}
