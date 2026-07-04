package org.vorpal.blade.services.tpcc.callflows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v3.media.CallflowHold;
import org.vorpal.blade.services.tpcc.v1.DialogAPI;
import org.vorpal.blade.services.tpcc.v1.DialogAPI.ResponseStuff;

/// Create-and-park: the REST-initiated 3PCC first leg (RFC 3725 Flow I).
///
/// The INVITE goes out OFFERLESS, so the party's 200 OK carries its real
/// offer, and per offer/answer the ACK must carry our answer — an RFC 3264
/// inactive SDP built from that offer ([CallflowHold#inactiveAnswerFor]):
/// media parked but recoverable, until `connectDialogs` re-INVITEs with the
/// other party's SDP and bridges the call. (This replaced the 2543-era
/// static "blackhole" offer the first INVITE used to carry.)
public class CreateDialog extends ClientCallflow {

	public CreateDialog() {

	}

	public void invoke(SipServletRequest invite) throws ServletException, IOException {

		sendRequest(invite, (inviteResponse) -> {

			if (successful(inviteResponse)) {

				SipServletRequest ack = inviteResponse.createAck();
				// answer the offer carried in the 200 (offerless INVITE);
				// bodiless only if the party itself sent no offer
				String answer = CallflowHold.inactiveAnswerFor(inviteResponse);
				if (answer != null) {
					ack.setContent(answer.getBytes(StandardCharsets.UTF_8), "application/sdp");
				} else {
					sipLogger.warning(inviteResponse,
							"CreateDialog: 200 OK carried no offer; ACK goes bodiless");
				}
				sendRequest(ack);

				ResponseStuff rstuff = DialogAPI.responseMap.remove(inviteResponse.getSession().getId());
				Response response = Response.status(inviteResponse.getStatus(), inviteResponse.getReasonPhrase())
						.build();
				rstuff.asyncResponse.resume(response);

			} else if (failure(inviteResponse)) {

				ResponseStuff rstuff = DialogAPI.responseMap.get(inviteResponse.getSession().getId());
				rstuff.asyncResponse
						.resume(Response.status(inviteResponse.getStatus(), inviteResponse.getReasonPhrase()).build());

			}
		});

	}

}
