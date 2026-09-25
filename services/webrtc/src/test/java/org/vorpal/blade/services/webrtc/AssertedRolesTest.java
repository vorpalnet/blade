package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.vorpal.blade.framework.sip.DetachedRequest;

/// The roles a browser's token carried, asserted on the calls it places.
public class AssertedRolesTest {

	private static DetachedRequest invite() throws Exception {
		return new DetachedRequest("INVITE", "sip:pat@vorpal.net", "sip:room1@meetings");
	}

	@Test
	public void theRolesTravelCommaSeparated() throws Exception {
		DetachedRequest invite = invite();
		OutboundFromBrowser.assertRoles(invite, Arrays.asList("Participant", "Host"));
		assertEquals("Participant, Host", invite.getHeader(OutboundFromBrowser.ASSERTED_ROLES));
	}

	@Test
	public void aRoleCannotBreakTheHeaderOrAddOne() throws Exception {
		DetachedRequest invite = invite();
		OutboundFromBrowser.assertRoles(invite, Arrays.asList("Host\r\nX-Evil: 1", "a,b"));
		assertEquals("HostX-Evil: 1, ab", invite.getHeader(OutboundFromBrowser.ASSERTED_ROLES));
	}

	@Test
	public void noRolesMeansNoHeader() throws Exception {
		DetachedRequest invite = invite();
		OutboundFromBrowser.assertRoles(invite, Collections.emptyList());
		OutboundFromBrowser.assertRoles(invite, null);
		assertNull(invite.getHeader(OutboundFromBrowser.ASSERTED_ROLES));
	}
}
