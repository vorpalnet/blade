package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/// Anchoring policy. The rule that matters is that RELAY is a *preference*, not an override: a
/// phone has no ICE and no DTLS-SRTP, so there is nothing for a browser to negotiate with directly
/// no matter what the deployment asked for.
public class MediaModeTest {

	@Test
	public void autoRelaysBetweenBrowsersAndAnchorsEverythingElse() {
		assertEquals(MediaMode.RELAY, MediaMode.AUTO.resolve(true));
		assertEquals(MediaMode.ANCHOR, MediaMode.AUTO.resolve(false));
	}

	@Test
	public void relayIsUpgradedToAnchorWhenOneEndIsAPhone() {
		// Honouring RELAY here would produce a call that cannot connect at all.
		assertEquals(MediaMode.ANCHOR, MediaMode.RELAY.resolve(false));
		assertEquals(MediaMode.RELAY, MediaMode.RELAY.resolve(true));
	}

	@Test
	public void anchorIsAbsolute() {
		// Recording, intercept and transcription all need the media, so this one is not negotiable
		// even when peer-to-peer would have worked.
		assertEquals(MediaMode.ANCHOR, MediaMode.ANCHOR.resolve(true));
		assertEquals(MediaMode.ANCHOR, MediaMode.ANCHOR.resolve(false));
	}
}
