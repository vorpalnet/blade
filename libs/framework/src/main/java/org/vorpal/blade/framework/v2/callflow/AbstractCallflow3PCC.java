package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

/// Base class for B2BUA-initiated re-INVITE callflows that change a leg's
/// media direction via third-party call control (RFC 3725 Flow I).
///
/// Caller invokes [#process(SipSession)] with the leg whose media state is
/// being changed (`alice`); the other leg (`bob`) is found via
/// [Callflow#getLinkedSession]. The flow:
///
/// 1. B2BUA → bob: re-INVITE with no SDP (delayed offer).
/// 2. B2BUA ← bob: 200 OK + bob's offer.
/// 3. Rewrite that offer's media direction to [#getMediaDirection].
/// 4. B2BUA → alice: re-INVITE with the rewritten offer.
/// 5. B2BUA ← alice: 200 OK + alice's answer.
/// 6. B2BUA → alice: ACK.
/// 7. B2BUA → bob: ACK + alice's answer.
///
/// Subclasses ([CallflowMute], [CallflowUnmute], [CallflowResume]) only
/// specify the target direction string. SDP-direction rewriting (single SDP
/// or `multipart/*`) is delegated to [SdpDirection].
public abstract class AbstractCallflow3PCC extends Callflow {

	private static final long serialVersionUID = 1L;

	/// SDP direction (`recvonly`, `sendrecv`, `sendonly`, `inactive`) to
	/// force on every m-line of bob's offer before re-INVITEing alice.
	protected abstract String getMediaDirection();

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		throw new ServletException(getClass().getSimpleName()
				+ " must be initiated via process(SipSession), not as a request handler.");
	}

	public void process(SipSession alice) throws ServletException, IOException {
		SipSession bob = getLinkedSession(alice);
		if (bob == null) {
			throw new ServletException(getClass().getSimpleName() + ": no linked session for alice");
		}

		SipServletRequest emptyToBob = bob.createRequest(INVITE);

		sendRequest(emptyToBob, bobResponse -> {
			Object obj = bobResponse.getContent();
			if (obj == null) {
				sipLogger.warning(bobResponse,
						getClass().getSimpleName() + ": bob's 200 OK had no SDP body; aborting");
				return;
			}
			byte[] body = (obj instanceof String) ? ((String) obj).getBytes() : (byte[]) obj;
			String contentType = bobResponse.getContentType();

			byte[] modBody;
			String modCt;
			try {
				if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
					SdpDirection.MultipartResult mp = SdpDirection.forceMultipart(body, contentType, getMediaDirection());
					modBody = mp.body;
					modCt = mp.contentType;
				} else {
					modBody = SdpDirection.force(new String(body), getMediaDirection()).getBytes();
					modCt = contentType;
				}
			} catch (Exception e) {
				sipLogger.warning(bobResponse, getClass().getSimpleName()
						+ ": failed to rewrite bob's offer to a=" + getMediaDirection() + " (" + e
						+ "); forwarding unchanged");
				modBody = body;
				modCt = contentType;
			}

			SipServletRequest invToAlice = alice.createRequest(INVITE);
			invToAlice.setContent(modBody, modCt);

			sendRequest(invToAlice, aliceResponse -> {
				sendRequest(aliceResponse.createAck());
				SipServletRequest bobAck = bobResponse.createAck();
				copyContentAndHeaders(aliceResponse, bobAck);
				sendRequest(bobAck);
			});
		});
	}
}
