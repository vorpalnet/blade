package org.vorpal.blade.framework.v3.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.sdp.Sdp;
import org.vorpal.blade.framework.v3.Callflow;

/// Answer a (re-)INVITE with a `200 OK` that parks the leg — the v3, RFC
/// 3264-style replacement for the v2 `CallflowHold` blackhole.
///
/// Where the v2 version echoed the caller's own SDP back with `c=0.0.0.0`
/// (the RFC 2543 hold convention — kept by RFC 3264 §8.4 only for
/// backward-compat receiving, and a known breaker of RTCP and ICE
/// middleboxes), this builds a PROPER answer of our own
/// ([SdpMedia#buildInactiveAnswer]): our `o=` line, our real address (the
/// interface the INVITE arrived on) with the discard port, one `a=inactive`
/// m-line per offered m-line with the offer's formats. `a=inactive` is a
/// legal answer to any offered direction, so no RTP flows either way and the
/// streams stay recoverable (non-zero port — port 0 would REJECT them).
///
/// RFC 3264 §8 o-line discipline: the session id is minted once per dialog
/// and kept on the [SipSession]; the version increments on every new answer.
/// **Offerless re-INVITEs** (RFC 4028 keep-alive refreshes) replay the exact
/// SDP last sent on the dialog — same version, since nothing changed — so
/// strict SBCs see a stable negotiation. Multipart SIPREC offers are
/// accepted; the answer is always plain `application/sdp`.
public class CallflowHold extends Callflow {
	private static final long serialVersionUID = 1L;

	/// Per-dialog [SipSession] attributes: the SDP last sent (replayed on
	/// offerless refreshes), and the o-line session id / version counter.
	public static final String LAST_SDP_ATTR = "org.vorpal.blade.v3.media.hold.lastSdp";
	public static final String SESS_ID_ATTR = "org.vorpal.blade.v3.media.hold.sessId";
	public static final String SESS_VERSION_ATTR = "org.vorpal.blade.v3.media.hold.sessVersion";

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(200);
		response.setHeader("Allow", "INVITE, ACK, BYE, CANCEL");

		String sessionExpires = request.getHeader("Session-Expires");
		if (sessionExpires != null) {
			if (!sessionExpires.toLowerCase().contains("refresher=")) {
				sessionExpires = sessionExpires + ";refresher=uac";
			}
			response.setHeader("Session-Expires", sessionExpires);
		}

		SipSession dialog = request.getSession();
		String answer = inactiveAnswerFor(request);

		if (answer == null) {
			// first INVITE on the dialog and it carried no offer: answer with
			// a minimal inactive audio SDP on OUR address (never 0.0.0.0)
			Sdp offer = new Sdp();
			Sdp.Media audio = new Sdp.Media();
			audio.setType("audio");
			audio.setPort(SdpMedia.DISCARD_PORT);
			audio.setProtocol("RTP/AVP");
			audio.setFormats(List.of("0"));
			audio.setAttributes(new java.util.ArrayList<>(
					List.of(new Sdp.Attribute("rtpmap", "0 PCMU/8000"))));
			offer.setMedia(List.of(audio));
			answer = SdpMedia.buildInactiveAnswer(offer, request.getLocalAddr(),
					sessionId(dialog), nextVersion(dialog)).toString();
		}

		dialog.setAttribute(LAST_SDP_ATTR, answer);
		response.setContent(answer.getBytes(StandardCharsets.UTF_8), "application/sdp");

		sendResponse(response, (ack) -> {
			// nothing further; the leg is parked
		});
	}

	/// Our inactive answer for `message`'s dialog, with the full per-dialog
	/// discipline: an offer in the message body (multipart accepted) gets a
	/// fresh [SdpMedia#buildInactiveAnswer] (stable o-line session id, version
	/// bumped, cached); no parseable offer replays the SDP last sent on this
	/// dialog byte-for-byte (an offerless refresh changes nothing, so the SDP
	/// must not either). Returns null only when there is neither an offer nor
	/// a cached answer — the caller picks the fallback.
	///
	/// Static and message-typed on purpose: the same logic answers a 200 OK
	/// (this class), a scripted test answer (`v3.tester.ScriptedAnswer`), and
	/// the ACK of an offerless 3PCC INVITE, where the offer arrives in a
	/// RESPONSE (tpcc's `CreateDialog`).
	public static String inactiveAnswerFor(javax.servlet.sip.SipServletMessage message) {
		SipSession dialog = message.getSession();
		try {
			String offerText = SdpMedia.extractSdp(message.getRawContent(), message.getContentType());
			if (offerText != null) {
				Sdp offer = Sdp.parse(offerText);
				String answer = SdpMedia.buildInactiveAnswer(offer, message.getLocalAddr(),
						sessionId(dialog), nextVersion(dialog)).toString();
				dialog.setAttribute(LAST_SDP_ATTR, answer);
				return answer;
			}
		} catch (Exception e) {
			sipLogger.warning("CallflowHold: could not build an answer from the offer ("
					+ e.getMessage() + "); falling back to replay");
		}
		Object cached = dialog.getAttribute(LAST_SDP_ATTR);
		return cached instanceof String ? (String) cached : null;
	}

	/// The o-line session id for this dialog — minted once, then stable.
	private static String sessionId(SipSession dialog) {
		Object id = dialog.getAttribute(SESS_ID_ATTR);
		if (id instanceof String) {
			return (String) id;
		}
		String minted = String.valueOf(System.currentTimeMillis());
		dialog.setAttribute(SESS_ID_ATTR, minted);
		return minted;
	}

	/// The o-line version for the NEXT answer on this dialog — increments per
	/// new answer, per RFC 3264 §8.
	private static long nextVersion(SipSession dialog) {
		Object v = dialog.getAttribute(SESS_VERSION_ATTR);
		long next = (v instanceof Long) ? (Long) v + 1 : 1;
		dialog.setAttribute(SESS_VERSION_ATTR, next);
		return next;
	}
}
