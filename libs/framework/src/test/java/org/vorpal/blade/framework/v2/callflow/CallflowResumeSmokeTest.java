package org.vorpal.blade.framework.v2.callflow;

import java.io.ByteArrayOutputStream;

import javax.activation.DataHandler;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

/// Smoke test for [CallflowResume]'s SDP-direction transform: every m-line
/// must end up `a=sendrecv` regardless of input direction. Mirrors
/// [CallflowHoldSmokeTest] structure.
public final class CallflowResumeSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		testFromInactive();
		testFromSendonly();
		testFromRecvonly();
		testSendrecvNoOp();
		testNoDirection();
		testMixedCase();
		testMultipleMediaLines();
		testMultipartSiprec();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	private static void testFromInactive() {
		String sdp = baseSdp("a=inactive\r\n");
		String out = SdpDirection.force(sdp, "sendrecv");
		check("inactive→sendrecv", everyMediaIsSendrecv(out));
		check("inactive.no-leftover", !out.contains("a=inactive"));
	}

	private static void testFromSendonly() {
		String sdp = baseSdp("a=sendonly\r\n");
		String out = SdpDirection.force(sdp, "sendrecv");
		check("sendonly→sendrecv", everyMediaIsSendrecv(out));
		check("sendonly.no-leftover", !out.contains("a=sendonly"));
	}

	private static void testFromRecvonly() {
		String sdp = baseSdp("a=recvonly\r\n");
		String out = SdpDirection.force(sdp, "sendrecv");
		check("recvonly→sendrecv", everyMediaIsSendrecv(out));
		check("recvonly.no-leftover", !out.contains("a=recvonly"));
	}

	private static void testSendrecvNoOp() {
		String sdp = baseSdp("a=sendrecv\r\n");
		String out = SdpDirection.force(sdp, "sendrecv");
		check("sendrecv.idempotent", everyMediaIsSendrecv(out));
		check("sendrecv.no-duplicate", countOccurrences(out, "a=sendrecv") == 1);
	}

	private static void testNoDirection() {
		String sdp = baseSdp("");
		String out = SdpDirection.force(sdp, "sendrecv");
		check("no-direction→sendrecv", everyMediaIsSendrecv(out));
	}

	private static void testMixedCase() {
		String sdp = baseSdp("a=Inactive\r\n");
		String out = SdpDirection.force(sdp, "sendrecv");
		check("mixed-case→sendrecv", everyMediaIsSendrecv(out));
		check("mixed-case.no-leftover", !out.contains("a=Inactive"));
	}

	private static void testMultipleMediaLines() {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=inactive\r\n"
				+ "m=audio 8002 RTP/AVP 0\r\n"
				+ "a=sendonly\r\n";
		String out = SdpDirection.force(sdp, "sendrecv");
		check("multi-mline.both-sendrecv", countOccurrences(out, "a=sendrecv") == 2);
		check("multi-mline.no-inactive", !out.contains("a=inactive"));
		check("multi-mline.no-sendonly", !out.contains("a=sendonly"));
	}

	private static void testMultipartSiprec() throws Exception {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 0.0.0.0\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 20098 RTP/AVP 0\r\n"
				+ "a=inactive\r\n"
				+ "m=audio 20102 RTP/AVP 0\r\n"
				+ "a=inactive\r\n";
		String xml = "<?xml version='1.0' encoding='UTF-8'?>\r\n"
				+ "<recording><apkt:ucid>DEADBEEF</apkt:ucid></recording>\r\n";

		MultipartBundle bundle = buildMultipart(new String[][] {
				{ "application/sdp", sdp },
				{ "application/rs-metadata+xml", xml }
		});

		SdpDirection.MultipartResult result =
				SdpDirection.forceMultipart(bundle.body, bundle.contentType, "sendrecv");

		String out = new String(result.body);
		check("siprec.boundary-preserved", boundaryFromCt(result.contentType) != null);
		check("siprec.both-sendrecv", countOccurrences(out, "a=sendrecv") == 2);
		check("siprec.no-inactive", !out.contains("a=inactive"));
		check("siprec.xml-preserved", out.contains("apkt:ucid"));
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

	private static boolean everyMediaIsSendrecv(String sdp) {
		String[] sections = sdp.split("(?=^m=)", -1);
		boolean ok = true;
		for (int i = 1; i < sections.length; i++) {
			String s = sections[i];
			if (!s.contains("a=sendrecv")) ok = false;
			for (String dir : new String[] { "inactive", "sendonly", "recvonly" }) {
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
