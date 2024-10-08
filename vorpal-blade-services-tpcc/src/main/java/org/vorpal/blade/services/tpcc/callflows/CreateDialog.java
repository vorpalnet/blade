package org.vorpal.blade.services.tpcc.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.vorpal.blade.framework.callflow.ClientCallflow;
import org.vorpal.blade.services.tpcc.v1.DialogAPI;
import org.vorpal.blade.services.tpcc.v1.DialogAPI.ResponseStuff;

public class CreateDialog extends ClientCallflow {

	public CreateDialog() {

	}

	public void invoke(SipServletRequest invite) throws ServletException, IOException {

		sendRequest(invite, (inviteResponse) -> {

			if (successful(inviteResponse)) {

				sendRequest(inviteResponse.createAck());

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
