package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.vorpal.blade.framework.v3.events.CloudEvent;

/// The browser wire format. Two things are load-bearing and easy to break silently: SDP must round
/// trip byte-identically (a relayed call hands one browser's SDP to the other untouched, and a
/// single mangled character breaks the DTLS handshake with no useful error), and the call id must
/// survive as the CloudEvents `subject`, because that is the application-session key a WebSocket
/// thread looks the call up by.
public class SignalProtocolTest {

	/// Structurally real: unified plan, DTLS, rtcp-mux, Opus and G.711, mDNS host candidate.
	private static final String SDP = "v=0\r\n"
			+ "o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n"
			+ "s=-\r\nt=0 0\r\n"
			+ "a=group:BUNDLE 0\r\n"
			+ "m=audio 9 UDP/TLS/RTP/SAVPF 111 0 8\r\n"
			+ "a=rtcp-mux\r\na=mid:0\r\n"
			+ "a=ice-ufrag:4ZcD\r\na=ice-pwd:2/1muCWoOi3uLifh0NuRHlZ6\r\n"
			+ "a=fingerprint:sha-256 AB:CD:EF\r\na=setup:actpass\r\n"
			+ "a=candidate:1 1 udp 2113937151 9b36eaac-bb2e.local 54321 typ host\r\n"
			+ "a=rtpmap:111 opus/48000/2\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n";

	@Test
	public void sdpSurvivesAnEnvelopeRoundTripByteForByte() throws Exception {
		CloudEvent sent = SignalProtocol.sdp(SignalProtocol.CALL_INCOMING, "call-1", SDP);

		CloudEvent received = CloudEvent.fromJson(sent.toJson());

		// Relay hands this straight to the other browser; any mangling breaks DTLS, not parsing,
		// so it would show up as "connects but no audio" rather than an error.
		assertEquals(SDP, SignalProtocol.field(received, "sdp"));
	}

	@Test
	public void mdnsCandidatesAndCrlfArePreserved() throws Exception {
		CloudEvent received = CloudEvent.fromJson(SignalProtocol.sdp(SignalProtocol.CALL_UPDATE, "c", SDP).toJson());
		String sdp = SignalProtocol.field(received, "sdp");

		assertTrue("mDNS host candidate must survive", sdp.contains("9b36eaac-bb2e.local"));
		assertTrue("CRLF line endings are required by RFC 4566", sdp.contains("\r\n"));
		assertTrue("the DTLS fingerprint is what makes the handshake work", sdp.contains("a=fingerprint:"));
	}

	@Test
	public void theCallIdTravelsAsTheSubject() throws Exception {
		// The subject is the application-session index key; losing it means a browser's answer has
		// nowhere to go.
		CloudEvent received = CloudEvent.fromJson(
				SignalProtocol.reason(SignalProtocol.CALL_ENDED, "call-42", "hung up").toJson());

		assertEquals("call-42", received.getSubject());
		assertEquals(SignalProtocol.CALL_ENDED, received.getType());
		assertEquals("hung up", SignalProtocol.field(received, "reason"));
	}

	@Test
	public void everyEnvelopeIsCloudEvents1() throws Exception {
		CloudEvent received = CloudEvent.fromJson(
				SignalProtocol.event(SignalProtocol.SESSION_READY, null, SignalProtocol.data()).toJson());

		assertEquals("1.0", received.getSpecversion());
		assertEquals(SignalProtocol.SOURCE, received.getSource());
		assertNull("session-scoped events belong to no call", received.getSubject());
	}

	@Test
	public void absentAndBlankFieldsBothReadAsNull() {
		CloudEvent event = SignalProtocol.event(SignalProtocol.CALL_OFFER, "c",
				SignalProtocol.data().put("target", "   "));

		// A browser sending an empty dial string must not become a call to nowhere.
		assertNull(SignalProtocol.field(event, "target"));
		assertNull(SignalProtocol.field(event, "sdp"));
	}
}
