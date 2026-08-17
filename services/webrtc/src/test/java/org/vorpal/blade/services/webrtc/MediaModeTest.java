package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/// Media policy resolution. The shape to protect: the caller cannot know what
/// will answer, so resolution uses only what is knowable at offer time — a
/// local socket for the target (proof of a browser) and whether a media server
/// exists. Signaling is not in play here at all; every call is SIP regardless.
public class MediaModeTest {

	@Test
	public void autoRelaysWhenTheTargetIsProvablyABrowser() {
		assertEquals(MediaMode.RELAY, MediaMode.AUTO.resolve(true, true));
		assertEquals(MediaMode.RELAY, MediaMode.AUTO.resolve(true, false));
	}

	@Test
	public void autoAnchorsUnknownTargetsWhenAMediaServerExists() {
		// The far end might be a phone; the anchored offer is the one both a
		// phone and a browser can answer.
		assertEquals(MediaMode.ANCHOR, MediaMode.AUTO.resolve(false, true));
	}

	@Test
	public void autoPassesThroughUnknownTargetsWhenNothingCouldAnchor() {
		// Anchoring without a media server can only fail; a pass-through offer
		// still connects to any WebRTC-capable answerer.
		assertEquals(MediaMode.RELAY, MediaMode.AUTO.resolve(false, false));
	}

	@Test
	public void explicitModesAreNotSecondGuessed() {
		// ANCHOR: recording, intercept and transcription all need the media.
		// RELAY: the deployment chose peer-to-peer knowing phones won't work —
		// the code can no longer see the far end, so it cannot "upgrade" safely.
		assertEquals(MediaMode.ANCHOR, MediaMode.ANCHOR.resolve(true, true));
		assertEquals(MediaMode.ANCHOR, MediaMode.ANCHOR.resolve(false, false));
		assertEquals(MediaMode.RELAY, MediaMode.RELAY.resolve(false, true));
		assertEquals(MediaMode.RELAY, MediaMode.RELAY.resolve(true, false));
	}
}
