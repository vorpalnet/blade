package org.vorpal.blade.framework.v3.media;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.vorpal.blade.framework.v2.sdp.Sdp;

/// Container-free smoke test for the v3 media SDP helpers: RFC 3264 direction
/// handling (perspective reverse, session-level stripping, capture/restore)
/// and the proper inactive ANSWER builder that replaces the v2 blackhole.
/// Run: `java -cp <classes:test-classes:deps> org.vorpal.blade.framework.v3.media.SdpMediaSmokeTest`
public class SdpMediaSmokeTest {

	private static int passed = 0;
	private static int failed = 0;

	private static final String OFFER = ""
			+ "v=0\r\n"
			+ "o=alice 2890844526 2890844526 IN IP4 198.51.100.1\r\n"
			+ "s=-\r\n"
			+ "c=IN IP4 198.51.100.1\r\n"
			+ "t=0 0\r\n"
			+ "a=sendonly\r\n"
			+ "m=audio 49170 RTP/AVP 0 8\r\n"
			+ "a=rtpmap:0 PCMU/8000\r\n"
			+ "a=rtpmap:8 PCMA/8000\r\n"
			+ "a=candidate:1 1 UDP 2130706431 198.51.100.1 49170 typ host\r\n"
			+ "m=video 51372 RTP/AVP 96\r\n"
			+ "a=rtpmap:96 H264/90000\r\n"
			+ "a=inactive\r\n"
			+ "m=application 0 RTP/AVP 97\r\n";

	public static void main(String[] args) throws Exception {

		// ---- perspective ----
		check("sendonly reverses to recvonly", MediaDirection.SENDONLY.reverse() == MediaDirection.RECVONLY);
		check("recvonly reverses to sendonly", MediaDirection.RECVONLY.reverse() == MediaDirection.SENDONLY);
		check("sendrecv/inactive are symmetric", MediaDirection.SENDRECV.reverse() == MediaDirection.SENDRECV
				&& MediaDirection.INACTIVE.reverse() == MediaDirection.INACTIVE);
		check("parse is case-insensitive and null-safe", MediaDirection.parse("SendOnly") == MediaDirection.SENDONLY
				&& MediaDirection.parse("rtpmap") == null && MediaDirection.parse(null) == null);

		// ---- capture: media-level overrides session-level, default sendrecv ----
		Sdp offer = Sdp.parse(OFFER);
		List<MediaDirection> captured = SdpMedia.captureDirections(offer);
		check("capture per m-line (session-level fallback)", captured.size() == 3
				&& captured.get(0) == MediaDirection.SENDONLY   // from session-level a=sendonly
				&& captured.get(1) == MediaDirection.INACTIVE   // media-level wins
				&& captured.get(2) == MediaDirection.SENDONLY); // session-level again

		// ---- force: session-level stripped, every m-line set ----
		SdpMedia.forceDirection(offer, MediaDirection.RECVONLY);
		String forced = offer.toString();
		check("session-level direction removed", !forced.contains("a=sendonly"));
		check("every m-line forced", count(forced, "a=recvonly") == 3 && !forced.contains("a=inactive"));
		check("non-direction attributes untouched", forced.contains("a=rtpmap:96 H264/90000")
				&& forced.contains("a=candidate:1"));
		SdpMedia.forceDirection(offer, MediaDirection.RECVONLY);
		check("force is idempotent (no duplicate attrs)", count(offer.toString(), "a=recvonly") == 3);
		Sdp mixedCase = Sdp.parse(OFFER.replace("a=sendonly", "a=SendOnly").replace("a=inactive", "a=InActive"));
		SdpMedia.forceDirection(mixedCase, MediaDirection.SENDRECV);
		String mixed = mixedCase.toString();
		check("mixed-case direction attrs replaced", !mixed.contains("a=SendOnly")
				&& !mixed.contains("a=InActive") && count(mixed, "a=sendrecv") == 3);

		// ---- apply/restore ----
		check("restore puts back per-stream state", SdpMedia.applyDirections(offer, captured));
		String restored = offer.toString();
		check("restored video is inactive again", restored.contains("a=inactive")
				&& count(restored, "a=sendonly") == 2);
		check("apply refuses a count mismatch",
				!SdpMedia.applyDirections(offer, List.of(MediaDirection.SENDRECV)));

		// ---- the answer builder (the blackhole replacement) ----
		Sdp freshOffer = Sdp.parse(OFFER);
		Sdp answer = SdpMedia.buildInactiveAnswer(freshOffer, "203.0.113.7", "17469", 3);
		String a = answer.toString();
		check("o-line is OURS with the given id/version", a.contains("o=- 17469 3 IN IP4 203.0.113.7"));
		check("c-line is our REAL address, never 0.0.0.0", a.contains("c=IN IP4 203.0.113.7")
				&& !a.contains("0.0.0.0"));
		check("one m-line per offered m-line, formats kept", a.contains("m=audio 9 RTP/AVP 0 8")
				&& a.contains("m=video 9 RTP/AVP 96"));
		check("offer-disabled stream stays port 0", a.contains("m=application 0 RTP/AVP 97"));
		check("every stream answered inactive", count(a, "a=inactive") == 3);
		check("rtpmap kept, offerer transport attrs dropped", a.contains("a=rtpmap:0 PCMU/8000")
				&& !a.contains("candidate"));
		check("ipv6 answer uses IP6", SdpMedia.buildInactiveAnswer(freshOffer, "2001:db8::9", "1", 1)
				.toString().contains("c=IN IP6 2001:db8::9"));

		// ---- body handling ----
		check("extract plain sdp", SdpMedia.extractSdp(OFFER.getBytes(StandardCharsets.UTF_8),
				"application/sdp") != null);
		check("extract null body / no ct", SdpMedia.extractSdp(null, "application/sdp") == null
				&& SdpMedia.extractSdp(OFFER.getBytes(StandardCharsets.UTF_8), null) == null);

		String boundary = "unique-boundary-1";
		String multipart = ""
				+ "--" + boundary + "\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ OFFER
				+ "--" + boundary + "\r\n"
				+ "Content-Type: application/rs-metadata+xml\r\n"
				+ "\r\n"
				+ "<recording/>\r\n"
				+ "--" + boundary + "--\r\n";
		String mpType = "multipart/mixed; boundary=" + boundary;

		String extracted = SdpMedia.extractSdp(multipart.getBytes(StandardCharsets.UTF_8), mpType);
		check("extract sdp part from multipart", extracted != null && extracted.contains("m=audio 49170"));

		SdpMedia.MultipartResult mp = SdpMedia.rewriteMultipart(multipart.getBytes(StandardCharsets.UTF_8),
				mpType, sdp -> SdpMedia.forceDirection(sdp, MediaDirection.INACTIVE));
		String rewritten = new String(mp.body, StandardCharsets.UTF_8);
		check("multipart: sdp part rewritten", count(rewritten, "a=inactive") == 3
				&& !rewritten.contains("a=sendonly"));
		check("multipart: metadata part preserved", rewritten.contains("<recording/>"));
		check("multipart: content-type has a boundary", mp.contentType.toLowerCase().contains("boundary="));

		System.out.println("SdpMediaSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
			n++;
		}
		return n;
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}
}
