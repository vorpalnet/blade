package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

/// The inbound media decision is content-based: an offer that carries a DTLS
/// fingerprint came from a WebRTC endpoint and is passed through; one without
/// came from a phone or trunk and is anchored. Getting this wrong either hands
/// a browser an offer it cannot key with, or pushes a browser-to-browser call
/// onto a media server that may not exist.
public class InboundToBrowserTest {

	private static byte[] sdp(String... lines) {
		return String.join("\r\n", lines).getBytes(StandardCharsets.UTF_8);
	}

	@org.junit.Test
	public void aBrowserOfferIsRecognizedByItsFingerprint() {
		assertTrue(InboundToBrowser.isWebrtcOffer(sdp(
				"v=0",
				"o=- 46117317 2 IN IP4 127.0.0.1",
				"m=audio 9 UDP/TLS/RTP/SAVPF 111",
				"a=ice-ufrag:F7gI",
				"a=fingerprint:sha-256 D2:FA:0E:C3:22:59:5E:14:95:69:92:3D:13:B4:84:24"
						+ ":2C:C2:A2:C0:3E:FD:34:8E:5E:EA:6F:AF:52:CE:E6:0F",
				"a=setup:actpass")));
	}

	@org.junit.Test
	public void aPhoneOfferIsNot() {
		assertFalse(InboundToBrowser.isWebrtcOffer(sdp(
				"v=0",
				"o=- 13760799956958020 13760799956958020 IN IP4 10.0.1.11",
				"m=audio 49172 RTP/AVP 0 8 101",
				"a=rtpmap:0 PCMU/8000")));
	}

}
