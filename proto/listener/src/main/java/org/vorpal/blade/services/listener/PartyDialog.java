package org.vorpal.blade.services.listener;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.media.MediaDirection;

/// A request on the dialog to a party brought into the call, which the
/// two-party B2BUA has no leg to relay to.
///
/// - `BYE`: the party left. Their leg comes off the mix and the call goes on.
/// - `INVITE` or `UPDATE`: a hold or a refresh, answered with the party's leg as
///   the media server has it, in the mirrored direction, as a re-INVITE on the
///   two main legs is ([AnchoredSdp]).
/// - anything else: `200`.
public class PartyDialog extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		String appId = request.getApplicationSession().getId();
		ListenerAnchor.Anchor anchor = ListenerAnchor.LIVE.get(appId);
		Object legUri = request.getSession().getAttribute(ListenerAnchor.PARTY);
		switch (request.getMethod()) {
		case "BYE":
			ListenerAnchor.dropParty(anchor, (legUri == null) ? null : legUri.toString());
			sipLogger.info(request, "PartyDialog: a party left " + (anchor == null ? appId : anchor.vorpalId));
			sendResponse(request.createResponse(200));
			return;
		case "INVITE":
		case "UPDATE":
			ListenerAnchor.Party party = (anchor == null || legUri == null) ? null : anchor.parties.get(legUri.toString());
			List<MediaDirection> offered = ListenerServlet.directionsOf(request);
			MediaDirection direction = (offered == null || offered.isEmpty()) ? MediaDirection.SENDRECV
					: offered.get(0).reverse();
			byte[] sdp = (party == null) ? null : ListenerAnchor.anchoredSdp(anchor, party.leg, direction);
			if (sdp == null) {
				sendResponse(request.createResponse(488, "Not Acceptable Here"));
				return;
			}
			SipServletResponse ok = request.createResponse(200);
			ok.setContent(sdp, "application/sdp");
			sendResponse(ok);
			return;
		default:
			sendResponse(request.createResponse(200));
		}
	}
}
