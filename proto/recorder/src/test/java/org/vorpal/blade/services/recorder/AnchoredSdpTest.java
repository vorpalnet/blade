package org.vorpal.blade.services.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.sdp.Sdp;
import org.vorpal.blade.framework.v3.media.MediaDirection;
import org.vorpal.blade.framework.v3.media.SdpMedia;

/// A re-INVITE keeps the media server's address and changes only the
/// direction, or the call leaves the media server behind.
public class AnchoredSdpTest {

	private static final String SERVER = "v=0\r\n"
			+ "o=- 3854 2 IN IP4 10.1.1.211\r\n"
			+ "s=Kurento Media Server\r\n"
			+ "c=IN IP4 10.1.1.211\r\n"
			+ "t=0 0\r\n"
			+ "m=audio 40012 RTP/AVP 0\r\n"
			+ "a=rtpmap:0 PCMU/8000\r\n"
			+ "a=sendrecv\r\n";

	private static Sdp rewritten(MediaDirection direction, long bump) {
		byte[] out = AnchoredSdp.rewrite(SERVER.getBytes(StandardCharsets.UTF_8), direction, bump);
		return Sdp.parse(new String(out, StandardCharsets.UTF_8));
	}

	@Test
	public void theAddressStaysAndTheDirectionChanges() {
		Sdp hold = rewritten(MediaDirection.SENDONLY, 1);
		assertEquals("10.1.1.211", hold.getConnection().getAddress());
		assertEquals(40012, hold.getMedia().get(0).getPort());
		assertEquals(List.of(MediaDirection.SENDONLY), SdpMedia.captureDirections(hold));
	}

	@Test
	public void theAnswerMirrorsTheOffer() {
		assertEquals(MediaDirection.RECVONLY, MediaDirection.SENDONLY.reverse());
		Sdp answer = rewritten(MediaDirection.SENDONLY.reverse(), 1);
		assertEquals(List.of(MediaDirection.RECVONLY), SdpMedia.captureDirections(answer));
	}

	@Test
	public void theVersionMoves() {
		assertEquals("3", rewritten(MediaDirection.SENDONLY, 1).getOrigin().getSessionVersion());
		assertEquals("4", rewritten(MediaDirection.SENDRECV, 2).getOrigin().getSessionVersion());
		assertEquals("abc7", AnchoredSdp.bumped("abc", 7));
	}

	@Test
	public void nothingNegotiatedMeansNothingRewritten() {
		assertNull(AnchoredSdp.rewrite(null, MediaDirection.SENDONLY, 1));
		assertNull(AnchoredSdp.rewrite(new byte[0], MediaDirection.SENDONLY, 1));
	}

	@Test
	public void aMissingDirectionMeansSendrecv() {
		assertTrue(SdpMedia.captureDirections(rewritten(null, 1)).contains(MediaDirection.SENDRECV));
	}
}
