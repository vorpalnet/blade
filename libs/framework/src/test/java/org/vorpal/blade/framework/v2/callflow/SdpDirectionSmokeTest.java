package org.vorpal.blade.framework.v2.callflow;

import java.io.ByteArrayOutputStream;

import javax.activation.DataHandler;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

/// Smoke test for [SdpDirection]'s SDP-direction transform. Verifies every
/// m-line in the output ends up `a=inactive` regardless of the input
/// direction, and that multipart bodies are unwrapped, transformed, and
/// repackaged so the response Content-Type's boundary still matches the body.
/// Used by [CallflowMute], [CallflowUnmute], [CallflowResume], and
/// [AbstractCallflow3PCC] to rewrite a peer-leg offer mid-flow.
public final class SdpDirectionSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		testSendrecv();
		testSendonly();
		testRecvonly();
		testInactiveNoOp();
		testNoDirection();
		testMixedCase();
		testMultipleMediaLines();
		testMultipartSiprec();
		testMultipartTwoSdpParts();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	private static void testSendrecv() {
		String sdp = baseSdp("a=sendrecv\r\n");
		String out = SdpDirection.force(sdp, "inactive");
		check("sendrecv→inactive", everyMediaIsInactive(out));
		check("sendrecv.no-leftover", !out.contains("a=sendrecv"));
	}

	private static void testSendonly() {
		String sdp = baseSdp("a=sendonly\r\n");
		String out = SdpDirection.force(sdp, "inactive");
		check("sendonly→inactive", everyMediaIsInactive(out));
		check("sendonly.no-leftover", !out.contains("a=sendonly"));
	}

	private static void testRecvonly() {
		String sdp = baseSdp("a=recvonly\r\n");
		String out = SdpDirection.force(sdp, "inactive");
		check("recvonly→inactive (mute case)", everyMediaIsInactive(out));
		check("recvonly.no-leftover", !out.contains("a=recvonly"));
	}

	private static void testInactiveNoOp() {
		String sdp = baseSdp("a=inactive\r\n");
		String out = SdpDirection.force(sdp, "inactive");
		check("inactive.idempotent", everyMediaIsInactive(out));
		// One inactive line per m-line, not duplicated.
		check("inactive.no-duplicate", countOccurrences(out, "a=inactive") == 1);
	}

	private static void testNoDirection() {
		String sdp = baseSdp("");
		String out = SdpDirection.force(sdp, "inactive");
		check("no-direction→inactive", everyMediaIsInactive(out));
	}

	private static void testMixedCase() {
		String sdp = baseSdp("a=SendRecv\r\n");
		String out = SdpDirection.force(sdp, "inactive");
		check("mixed-case→inactive", everyMediaIsInactive(out));
		check("mixed-case.no-leftover", !out.contains("a=SendRecv"));
	}

	private static void testMultipleMediaLines() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=sendonly\r\n"
				+ "m=audio 8002 RTP/AVP 0\r\n"
				+ "a=recvonly\r\n";
		String out = SdpDirection.force(sdp, "inactive");
		check("multi-mline.both-inactive", countOccurrences(out, "a=inactive") == 2);
		check("multi-mline.no-sendonly", !out.contains("a=sendonly"));
		check("multi-mline.no-recvonly", !out.contains("a=recvonly"));
	}

	private static void testMultipartSiprec() throws Exception {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 20098 RTP/AVP 0\r\n"
				+ "a=sendonly\r\n"
				+ "m=audio 20102 RTP/AVP 0\r\n"
				+ "a=sendrecv\r\n";
		String xml = "<?xml version='1.0' encoding='UTF-8'?>\r\n"
				+ "<recording><apkt:ucid>DEADBEEF</apkt:ucid></recording>\r\n";

		MultipartBundle bundle = buildMultipart(new String[][] {
				{ "application/sdp", sdp },
				{ "application/rs-metadata+xml", xml }
		});

		SdpDirection.MultipartResult result =
				SdpDirection.forceMultipart(bundle.body, bundle.contentType, "inactive");

		String roundtripped = new String(result.body);
		check("siprec.boundary-preserved", boundaryFromCt(result.contentType) != null);
		check("siprec.both-inactive", countOccurrences(roundtripped, "a=inactive") == 2);
		check("siprec.no-sendonly", !roundtripped.contains("a=sendonly"));
		check("siprec.no-sendrecv", !roundtripped.contains("a=sendrecv"));
		check("siprec.xml-preserved", roundtripped.contains("apkt:ucid"));
	}

	private static void testMultipartTwoSdpParts() throws Exception {
		String sdp1 = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=sendrecv\r\n";
		String sdp2 = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8002 RTP/AVP 0\r\n"
				+ "a=recvonly\r\n";

		MultipartBundle bundle = buildMultipart(new String[][] {
				{ "application/sdp", sdp1 },
				{ "application/sdp", sdp2 }
		});

		SdpDirection.MultipartResult result =
				SdpDirection.forceMultipart(bundle.body, bundle.contentType, "inactive");

		String out = new String(result.body);
		check("two-sdp.both-transformed", countOccurrences(out, "a=inactive") == 2);
		check("two-sdp.no-sendrecv", !out.contains("a=sendrecv"));
		check("two-sdp.no-recvonly", !out.contains("a=recvonly"));
	}

	// ----- helpers ---------------------------------------------------

	private static String baseSdp(String mediaDirectionLine) {
		return "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n"
				+ mediaDirectionLine;
	}

	/// Asserts every `m=` section contains an `a=inactive` line and no other
	/// direction attribute. Returns true on success.
	private static boolean everyMediaIsInactive(String sdp) {
		String[] sections = sdp.split("(?=^m=)", -1);
		// sections[0] is session-level prefix; m-line sections start at [1].
		boolean ok = true;
		for (int i = 1; i < sections.length; i++) {
			String s = sections[i];
			if (!s.contains("a=inactive")) ok = false;
			for (String dir : new String[] { "sendrecv", "sendonly", "recvonly" }) {
				if (s.toLowerCase().contains("a=" + dir)) ok = false;
			}
		}
		return ok;
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) != -1) {
			count++;
			idx += needle.length();
		}
		return count;
	}

	private static String boundaryFromCt(String contentType) {
		int b = contentType.toLowerCase().indexOf("boundary=");
		if (b < 0) return null;
		String tail = contentType.substring(b + "boundary=".length()).trim();
		if (tail.startsWith("\"")) {
			int end = tail.indexOf('"', 1);
			return (end > 0) ? tail.substring(1, end) : null;
		}
		int semi = tail.indexOf(';');
		return (semi >= 0) ? tail.substring(0, semi).trim() : tail;
	}

	private static MultipartBundle buildMultipart(String[][] parts) throws Exception {
		MimeMultipart mp = new MimeMultipart("mixed");
		for (String[] p : parts) {
			InternetHeaders h = new InternetHeaders();
			h.addHeader("Content-Type", p[0]);
			MimeBodyPart bp = new MimeBodyPart();
			bp.setDataHandler(new DataHandler(new ByteArrayDataSource(p[1].getBytes(), p[0])));
			bp.setHeader("Content-Type", p[0]);
			mp.addBodyPart(bp);
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		mp.writeTo(baos);
		return new MultipartBundle(baos.toByteArray(), mp.getContentType());
	}

	private static final class MultipartBundle {
		final byte[] body;
		final String contentType;
		MultipartBundle(byte[] body, String contentType) {
			this.body = body;
			this.contentType = contentType;
		}
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
}
