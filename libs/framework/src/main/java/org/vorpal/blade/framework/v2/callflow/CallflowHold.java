package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

/// Answer a (re-)INVITE with `200 OK` whose every m-line has `a=inactive`,
/// putting the leg on hold. Counterpart to [CallflowResume].
public class CallflowHold extends Callflow {

	private static final long serialVersionUID = 1L;

	private static final String LAST_BODY = "CallflowHold.lastBody";
	private static final String LAST_CONTENT_TYPE = "CallflowHold.lastContentType";

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

		SipSession session = request.getSession();
		Object obj = request.getContent();
		byte[] body = null;
		String contentType = request.getContentType();

		if (obj != null) {
			body = (obj instanceof String) ? ((String) obj).getBytes() : (byte[]) obj;
		} else {
			// Delayed-offer (re-)INVITE — UAS owes an offer in the 200 OK. Reuse the
			// last body we sent on this leg, kept on the SipSession by the previous
			// successful pass through this method.
			body = (byte[]) session.getAttribute(LAST_BODY);
			contentType = (String) session.getAttribute(LAST_CONTENT_TYPE);
		}

		if (body != null && contentType != null) {
			try {
				byte[] outBody;
				String outCt;
				if (contentType.toLowerCase().startsWith("multipart/")) {
					SdpDirection.MultipartResult mp = SdpDirection.forceMultipart(body, contentType, "inactive");
					outBody = mp.body;
					outCt = mp.contentType;
				} else {
					outBody = SdpDirection.force(new String(body), "inactive").getBytes();
					outCt = contentType;
				}
				response.setContent(outBody, outCt);
				session.setAttribute(LAST_BODY, outBody);
				session.setAttribute(LAST_CONTENT_TYPE, outCt);
			} catch (Exception e) {
				sipLogger.warning(request,
						"CallflowHold: failed to force a=inactive (" + e + "); sending original body back");
				response.setContent(body, contentType);
			}
		}

		sendResponse(response, (ack) -> {
			// do nothing
		});
	}

}
