package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/// Dial-string handling. A browser dials a phone number; SIP needs a URI. Getting that translation
/// wrong is the difference between a call routing and a `404`, and it is the one piece of outbound
/// origination that can be checked without a SIP container.
public class OutboundFromBrowserTest {

	@Test
	public void bareNumbersInheritTheCallersDomain() {
		// Otherwise "sip:13125551212" has no host and cannot be routed at all.
		assertEquals("sip:13125551212@example.com",
				OutboundFromBrowser.normalizeTarget("13125551212", "alice@example.com"));
	}

	@Test
	public void plusPrefixedNumbersAreLeftIntact() {
		assertEquals("sip:+13125551212@example.com",
				OutboundFromBrowser.normalizeTarget("+13125551212", "alice@example.com"));
	}

	@Test
	public void anExplicitDomainWins() {
		assertEquals("sip:bob@other.example",
				OutboundFromBrowser.normalizeTarget("bob@other.example", "alice@example.com"));
	}

	@Test
	public void fullUrisPassThroughUntouched() {
		assertEquals("sip:bob@other.example;transport=tcp",
				OutboundFromBrowser.normalizeTarget("sip:bob@other.example;transport=tcp", "alice@example.com"));
		assertEquals("sips:bob@other.example",
				OutboundFromBrowser.normalizeTarget("sips:bob@other.example", "alice@example.com"));
		assertEquals("tel:+13125551212",
				OutboundFromBrowser.normalizeTarget("tel:+13125551212", "alice@example.com"));
	}

	@Test
	public void surroundingWhitespaceIsIgnored() {
		// Copy-pasted numbers routinely carry it.
		assertEquals("sip:13125551212@example.com",
				OutboundFromBrowser.normalizeTarget("  13125551212  ", "alice@example.com"));
	}

	@Test
	public void aCallerWithoutADomainStillProducesAUri() {
		assertEquals("sip:13125551212", OutboundFromBrowser.normalizeTarget("13125551212", "alice"));
		assertEquals("sip:13125551212", OutboundFromBrowser.normalizeTarget("13125551212", null));
	}

	// ---- targetAor: what the media-mode decision asks BrowserRegistry about --------------------

	@Test
	public void targetAorReducesEveryDialableFormToUserAtHost() {
		// However the target was typed, the socket-table lookup key is the same.
		assertEquals("bob@vorpal.net", OutboundFromBrowser.targetAor("bob@vorpal.net", "alice@vorpal.net"));
		assertEquals("bob@vorpal.net", OutboundFromBrowser.targetAor("sip:bob@vorpal.net", "alice@vorpal.net"));
		assertEquals("bob@vorpal.net",
				OutboundFromBrowser.targetAor("sip:bob@vorpal.net;transport=tcp", "alice@vorpal.net"));
		assertEquals("bob@vorpal.net", OutboundFromBrowser.targetAor("Bob@Vorpal.Net", "alice@vorpal.net"));
	}

	@Test
	public void targetAorGivesABareNumberTheCallersDomain() {
		// Matches normalizeTarget, so "dial 1234" and a browser registered as
		// 1234@vorpal.net find each other.
		assertEquals("1234@vorpal.net", OutboundFromBrowser.targetAor("1234", "alice@vorpal.net"));
	}

	@Test
	public void telTargetsAreNeverBrowsers() {
		// A tel: URI stays as-is, which matches no AOR in the socket table.
		assertEquals("tel:+13125551212", OutboundFromBrowser.targetAor("tel:+13125551212", "alice@vorpal.net"));
	}
}
