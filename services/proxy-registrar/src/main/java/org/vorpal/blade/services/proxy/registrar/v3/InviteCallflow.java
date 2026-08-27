package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v3.Callflow;

/// Forks an initial INVITE to every contact registered for the account — a
/// forking B2BUA built on [Callflow#sendRequestsInParallel]: the first 2xx
/// wins and the framework CANCELs the losing dialogs. With `session:passthru`
/// set, the winning dialog drops out of the dialog after setup.
public class InviteCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 397213565821542521L;

	@Override
	public void process(SipServletRequest aliceRequest) throws ServletException, IOException {

		String accountName = AsyncSipServlet.getAccountName(aliceRequest.getRequestURI());

		SipApplicationSession registrarSession = sipUtil.getApplicationSessionByKey(accountName, false);

		List<URI> contacts = null;
		if (registrarSession != null) {
			Registrar registrar = (Registrar) registrarSession.getAttribute("registrar");
			if (registrar != null) {
				contacts = registrar.getContacts(aliceRequest);
			}
		}

		Settings settings = PRServlet.settingsManager.getCurrent();

		if ((contacts == null || contacts.isEmpty()) && Boolean.TRUE.equals(settings.getProxyOnUnregistered())) {
			contacts = new LinkedList<>();
			contacts.add(aliceRequest.getRequestURI());
		}

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(aliceRequest,
					"InviteCallflow.process - accountName=" + accountName + ", contacts=" + contacts);
		}

		if (contacts == null || contacts.isEmpty()) {
			// Only true wisdom consists in knowing that you know nothing.
			sendResponse(aliceRequest.createResponse(404));
			return;
		}

		// sendRequestsInParallel doesn't surface the dialogs' provisional
		// responses, so ring the caller immediately — their UA generates
		// local ringback until a dialog answers.
		sendResponse(aliceRequest.createResponse(180));

		SipApplicationSession appSession = aliceRequest.getApplicationSession();

		List<SipServletRequest> bobRequests = new LinkedList<>();
		for (URI contact : contacts) {
			SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, aliceRequest.getFrom(),
					aliceRequest.getTo());
			bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
			copyContentAndHeaders(aliceRequest, bobRequest); // links each dialog back to alice
			bobRequest.setRequestURI(contact);
			bobRequests.add(bobRequest);
		}

		long timeout = (settings.getTimeout() != null) ? settings.getTimeout() * 1000L : 0;

		// relay real ringing (180/183) from any dialog upstream, on top of the
		// immediate 180 above; 100 Trying is hop-by-hop
		Callback<SipServletResponse> dialogObserver = (dialogResponse) -> {
			int status = dialogResponse.getStatus();
			if (status > 100 && status < 200 && !aliceRequest.isCommitted()) {
				SipServletResponse aliceRinging = aliceRequest.createResponse(status);
				copyContentAndHeaders(dialogResponse, aliceRinging);
				sendResponse(aliceRinging);
			}
		};

		sendRequestsInParallel(timeout, bobRequests, (bobResponse) -> {

			if (!aliceRequest.isCommitted()) {

				SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
				copyContentAndHeaders(bobResponse, aliceResponse); // links alice to the winning dialog

				if (successful(bobResponse)) {
					sendResponse(aliceResponse, (aliceAck) -> {
						sendAcknowledgement(aliceAck, bobResponse);
					});
				} else {
					sendResponse(aliceResponse);
				}

			}

		}, dialogObserver);

	}

}
