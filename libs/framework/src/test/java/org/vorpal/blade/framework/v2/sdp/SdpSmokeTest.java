package org.vorpal.blade.framework.v2.sdp;

import java.util.Arrays;

/// Smoke-test driver for [Sdp]. Verifies lossless round-trip on real-world
/// SDP shapes — every field that goes in must come back out byte-for-byte
/// after canonicalization, and unmodified fields are never silently dropped.
public final class SdpSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testMinimal();
		testTypicalAudio();
		testAudioVideoWithBandwidth();
		testFlagAttributes();
		testInfoUriEmailPhone();
		testKey();
		testMultipartTimes();
		testMulticastConnection();
		testMediaInfoBandwidthKey();
		testMutateAttribute();
		testMutatePort();
		testRoundTripCanonicalOrder();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	private static void testMinimal() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n";
		check("minimal.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testTypicalAudio() {
		String sdp = "v=0\r\n"
				+ "o=alice 12345 67890 IN IP4 10.0.0.1\r\n"
				+ "s=Call\r\n"
				+ "c=IN IP4 10.0.0.1\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0 8 96\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n"
				+ "a=rtpmap:8 PCMA/8000\r\n"
				+ "a=rtpmap:96 telephone-event/8000\r\n"
				+ "a=fmtp:96 0-15\r\n"
				+ "a=sendrecv\r\n";
		check("typical-audio.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testAudioVideoWithBandwidth() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.2.3.4\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.2.3.4\r\n"
				+ "b=AS:128\r\n"
				+ "t=0 0\r\n"
				+ "a=group:BUNDLE audio video\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "b=AS:64\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n"
				+ "m=video 8002 RTP/AVP 96\r\n"
				+ "b=TIAS:96000\r\n"
				+ "a=rtpmap:96 H264/90000\r\n";
		check("av-bandwidth.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testFlagAttributes() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=sendonly\r\n"
				+ "a=ice-lite\r\n";
		Sdp parsed = Sdp.parse(sdp);
		// Flag attributes must have null value, not empty string
		Sdp.Attribute first = parsed.getMedia().get(0).getAttributes().get(0);
		check("flag.name", "sendonly".equals(first.getName()));
		check("flag.value-null", first.getValue() == null);
		check("flag.roundtrip", sdp, parsed.toString());
	}

	private static void testInfoUriEmailPhone() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=Demo\r\n"
				+ "i=A demo session\r\n"
				+ "u=https://example.com/session\r\n"
				+ "e=admin@example.com\r\n"
				+ "p=+1 555 1212\r\n"
				+ "t=0 0\r\n";
		check("info-uri-email-phone.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testKey() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "k=clear:secret\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "k=prompt\r\n";
		check("key.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testMultipartTimes() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=3034423619 3042462419\r\n"
				+ "r=604800 3600 0 90000\r\n"
				+ "t=0 0\r\n"
				+ "z=2882844526 -1h 2898848070 0\r\n";
		check("multipart-times.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testMulticastConnection() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 224.2.36.42/127/3\r\n"
				+ "t=0 0\r\n";
		check("multicast.roundtrip", sdp, Sdp.parse(sdp).toString());
		check("multicast.address",
				"224.2.36.42/127/3".equals(Sdp.parse(sdp).getConnection().getAddress()));
	}

	private static void testMediaInfoBandwidthKey() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "i=Telephone audio\r\n"
				+ "c=IN IP4 5.6.7.8\r\n"
				+ "b=AS:64\r\n"
				+ "k=clear:abcd\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n";
		check("media-extras.roundtrip", sdp, Sdp.parse(sdp).toString());
	}

	private static void testMutateAttribute() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=sendrecv\r\n";
		Sdp parsed = Sdp.parse(sdp);
		parsed.getMedia().get(0).getAttributes().get(0).setName("inactive");
		String out = parsed.toString();
		check("mutate-attr.contains-inactive", out.contains("a=inactive"));
		check("mutate-attr.no-sendrecv", !out.contains("a=sendrecv"));
	}

	private static void testMutatePort() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n";
		Sdp parsed = Sdp.parse(sdp);
		parsed.getMedia().get(0).setPort(9000);
		check("mutate-port.contains", parsed.toString().contains("m=audio 9000 RTP/AVP 0\r\n"));
	}

	/// Lines that arrive out of canonical RFC order must be re-emitted in the
	/// correct order. Important because real-world SDPs sometimes have
	/// session-level a= lines after media — we still emit them before m=.
	private static void testRoundTripCanonicalOrder() {
		String input = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "c=IN IP4 1.2.3.4\r\n"
				+ "a=group:BUNDLE audio\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n";
		String expected = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.2.3.4\r\n"
				+ "t=0 0\r\n"
				+ "a=group:BUNDLE audio\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n";
		check("canonical-order", expected, Sdp.parse(input).toString());
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}

	private static void check(String name, String expected, String actual) {
		if (expected.equals(actual)) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
			System.out.println("  expected:");
			for (String line : expected.split("\r\n")) System.out.println("    | " + line);
			System.out.println("  actual:");
			for (String line : actual.split("\r\n")) System.out.println("    | " + line);
		}
	}

	@SuppressWarnings("unused")
	private static void unused() { Arrays.asList(""); }
}
