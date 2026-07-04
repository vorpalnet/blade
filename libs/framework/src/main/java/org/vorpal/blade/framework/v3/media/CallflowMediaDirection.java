package org.vorpal.blade.framework.v3.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.sdp.Sdp;
import org.vorpal.blade.framework.v3.Callflow;

/// B2BUA-initiated re-INVITE that changes one leg's media direction via
/// third-party call control (RFC 3725 Flow I) — the v3 replacement for the v2
/// `AbstractCallflow3PCC` family, with the three defects of that code fixed:
///
/// 1. **Perspective.** [#targetDirection] is what the TARGET leg should DO,
///    and the offer presented to it is [MediaDirection#reverse]d, as RFC 3264
///    §6.1's answer rules require. (The v2 `CallflowMute` forced `recvonly`
///    into the offer, which makes the target the SENDER — it muted the wrong
///    leg.)
/// 2. **Failure handling.** Non-2xx from either leg is handled: the v2 code
///    called `createAck()` on any response, which throws on a 486 or a 491 —
///    and re-INVITE glare (491) is routine for a B2BUA. If the target rejects
///    the rewritten offer, the peer's open offer is still answered (media
///    parked `inactive` — see [#process(SipSession)]), never left hanging.
/// 3. **State.** Session-level direction attributes are stripped, not left to
///    contradict the per-m-line ones; and the pre-change per-stream directions
///    are captured on the target's [SipSession], so a restoring flow
///    ([CallflowResume]) puts back what was there instead of blanket
///    `a=sendrecv`-ing streams that were never active.
///
/// The wire flow (`target` = the leg whose media state changes, `peer` = its
/// [Callflow#getLinkedSession] counterpart):
///
/// 1. B2BUA → peer: re-INVITE, no SDP (delayed offer).
/// 2. B2BUA ← peer: 200 OK + peer's offer.
/// 3. Rewrite the offer's directions (reverse of [#targetDirection], or the
///    captured prior state when restoring).
/// 4. B2BUA → target: re-INVITE + rewritten offer.
/// 5. B2BUA ← target: 200 OK + target's answer → ACK target, ACK peer with
///    the answer.
///
/// Multipart bodies (SIPREC) pass through with only the SDP part rewritten.
/// Wrappers: [CallflowMute], [CallflowUnmute], [CallflowResume].
public class CallflowMediaDirection extends Callflow {
	private static final long serialVersionUID = 1L;

	/// Target-leg [SipSession] attribute holding the comma-joined per-m-line
	/// directions captured before the last non-restoring change — what a
	/// restoring flow puts back.
	public static final String PRIOR_DIRECTIONS_ATTR = "org.vorpal.blade.v3.media.priorDirections";

	private final MediaDirection targetDirection;
	private final boolean restorePrior;

	/// Drive the target leg to `targetDirection`.
	public CallflowMediaDirection(MediaDirection targetDirection) {
		this(targetDirection, false);
	}

	/// With `restorePrior`, the directions captured before the last change are
	/// put back (per m-line); `targetDirection` is the fallback when nothing
	/// was captured or the m-line count changed.
	public CallflowMediaDirection(MediaDirection targetDirection, boolean restorePrior) {
		this.targetDirection = targetDirection;
		this.restorePrior = restorePrior;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		throw new ServletException(getClass().getSimpleName()
				+ " must be initiated via process(SipSession), not as a request handler.");
	}

	public void process(SipSession target) throws ServletException, IOException {
		SipSession peer = getLinkedSession(target);
		if (peer == null) {
			throw new ServletException(getClass().getSimpleName() + ": no linked session for the target leg");
		}

		SipServletRequest emptyToPeer = peer.createRequest(INVITE);

		sendRequest(emptyToPeer, peerResponse -> {
			if (peerResponse.getStatus() < 200) {
				return; // provisional; wait for the final response
			}
			if (!successful(peerResponse)) {
				// non-2xx to a re-INVITE: the container ACKs it; media unchanged
				sipLogger.warning(peerResponse, getClass().getSimpleName()
						+ ": peer rejected the delayed-offer re-INVITE with " + peerResponse.getStatus()
						+ "; media unchanged");
				return;
			}

			Object content = peerResponse.getContent();
			if (content == null) {
				// peer violated delayed-offer rules; ACK its 2xx anyway so the
				// dialog isn't left retransmitting, and abort
				sipLogger.warning(peerResponse, getClass().getSimpleName()
						+ ": peer's 200 OK carried no offer; aborting");
				sendRequest(peerResponse.createAck());
				return;
			}
			byte[] body = (content instanceof String)
					? ((String) content).getBytes(StandardCharsets.UTF_8)
					: (byte[]) content;
			String contentType = peerResponse.getContentType();

			Rewritten offer;
			try {
				offer = rewriteOffer(body, contentType, target);
			} catch (Exception e) {
				sipLogger.warning(peerResponse, getClass().getSimpleName()
						+ ": failed to rewrite peer's offer (" + e + "); forwarding unchanged");
				offer = new Rewritten(body, contentType);
			}

			SipServletRequest invToTarget = target.createRequest(INVITE);
			invToTarget.setContent(offer.body, offer.contentType);

			sendRequest(invToTarget, targetResponse -> {
				if (targetResponse.getStatus() < 200) {
					return;
				}
				if (successful(targetResponse)) {
					sendRequest(targetResponse.createAck());
					SipServletRequest peerAck = peerResponse.createAck();
					copyContentAndHeaders(targetResponse, peerAck);
					sendRequest(peerAck);
					if (restorePrior) {
						target.removeAttribute(PRIOR_DIRECTIONS_ATTR);
					}
				} else {
					// The target rejected the rewritten offer (486, 491 glare,
					// ...). The container ACKs the failure toward the target,
					// but the PEER's offer is still open and its 200 is
					// retransmitting — it must be answered. We have no SDP of
					// the target's to answer with, so park the peer's own offer
					// `a=inactive`: legal against any offer, and no RTP flows.
					// Logged severe — the call needs an operator/app retry.
					sipLogger.severe(targetResponse, getClass().getSimpleName()
							+ ": target rejected the re-INVITE with " + targetResponse.getStatus()
							+ "; answering peer's open offer with a=inactive (media parked)");
					SipServletRequest peerAck = peerResponse.createAck();
					try {
						Rewritten parked = rewrite(peerResponse.getRawContent(), peerResponse.getContentType(),
								sdp -> SdpMedia.forceDirection(sdp, MediaDirection.INACTIVE));
						peerAck.setContent(parked.body, parked.contentType);
					} catch (Exception e) {
						sipLogger.severe(targetResponse, getClass().getSimpleName()
								+ ": could not build the parked answer (" + e + "); ACK goes bodiless");
					}
					sendRequest(peerAck);
				}
			});
		});
	}

	/// Rewrite the peer's offer for the target: restore the captured prior
	/// directions when restoring (fallback to [#targetDirection] if none fit),
	/// otherwise capture the current directions on the target session and
	/// force the reverse of [#targetDirection].
	private Rewritten rewriteOffer(byte[] body, String contentType, SipSession target) throws Exception {
		return rewrite(body, contentType, sdp -> {
			if (restorePrior) {
				List<MediaDirection> prior = readPrior(target);
				if (prior == null || !SdpMedia.applyDirections(sdp, prior)) {
					SdpMedia.forceDirection(sdp, targetDirection.reverse());
				}
			} else {
				storePrior(target, SdpMedia.captureDirections(sdp));
				SdpMedia.forceDirection(sdp, targetDirection.reverse());
			}
		});
	}

	private static Rewritten rewrite(byte[] body, String contentType, SdpMedia.SdpRewriter rewriter)
			throws Exception {
		if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
			SdpMedia.MultipartResult mp = SdpMedia.rewriteMultipart(body, contentType, rewriter);
			return new Rewritten(mp.body, mp.contentType);
		}
		Sdp sdp = Sdp.parse(new String(body, StandardCharsets.UTF_8));
		rewriter.rewrite(sdp);
		return new Rewritten(sdp.toString().getBytes(StandardCharsets.UTF_8), contentType);
	}

	private static void storePrior(SipSession target, List<MediaDirection> directions) {
		StringBuilder csv = new StringBuilder();
		for (MediaDirection d : directions) {
			if (csv.length() > 0) {
				csv.append(',');
			}
			csv.append(d.sdp());
		}
		target.setAttribute(PRIOR_DIRECTIONS_ATTR, csv.toString());
	}

	private static List<MediaDirection> readPrior(SipSession target) {
		Object csv = target.getAttribute(PRIOR_DIRECTIONS_ATTR);
		if (!(csv instanceof String) || ((String) csv).isEmpty()) {
			return null;
		}
		List<MediaDirection> out = new ArrayList<>();
		for (String name : ((String) csv).split(",")) {
			MediaDirection d = MediaDirection.parse(name);
			if (d == null) {
				return null;
			}
			out.add(d);
		}
		return out;
	}

	private static final class Rewritten {
		final byte[] body;
		final String contentType;

		Rewritten(byte[] body, String contentType) {
			this.body = body;
			this.contentType = contentType;
		}
	}
}
