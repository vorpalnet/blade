package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/// The pure pieces of the register-on-behalf path. The interesting part of the
/// contact — this engine's interface plus the container's `encodeURI` targeting
/// parameters — needs a live container and is verified in deployment; what can
/// break silently here is the AOR arithmetic feeding it.
public class BrowserRegistrationTest {

	@Test
	public void registerIsAimedAtTheAorsDomain() {
		assertEquals("sip:vorpal.net", BrowserRegistration.registrarUri("alice@vorpal.net"));
	}

	@Test
	public void userAndHostSplitAtTheLastAt() {
		assertEquals("alice", BrowserRegistration.userOf("alice@vorpal.net"));
		assertEquals("vorpal.net", BrowserRegistration.hostOf("alice@vorpal.net"));
		// AddressPolicy admits these in the user part; the split stays at the
		// LAST '@'.
		assertEquals("a.b+c!d", BrowserRegistration.userOf("a.b+c!d@example.co.uk"));
		assertEquals("example.co.uk", BrowserRegistration.hostOf("a.b+c!d@example.co.uk"));
		assertEquals("h", BrowserRegistration.hostOf("weird@user@h"));
	}

	@Test
	public void degenerateAddressesDoNotThrow() {
		// Upstream validation should make these unreachable; if one slips
		// through, the REGISTER simply goes nowhere useful — no exception on a
		// WebSocket thread.
		assertEquals("nohost", BrowserRegistration.hostOf("nohost"));
		assertEquals("nohost", BrowserRegistration.userOf("nohost"));
		assertEquals("", BrowserRegistration.hostOf("user@"));
	}
}
