package org.vorpal.blade.framework.v2.proxy;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

/**
 * Callflow for proxying INVITE requests through configured tiers.
 * Handles proxy plan execution, response callbacks, and tier failover.
 */
public class ProxyInvite extends Callflow {
	private static final long serialVersionUID = 1L;

	/** SIP response code for Bad Gateway */
	private static final int RESPONSE_BAD_GATEWAY = 502;
	/** Request attribute name to skip processing */
	private static final String ATTR_DO_NOT_PROCESS = "doNotProcess";

	private final ProxyListener proxyListener;
	private ProxyPlan proxyPlan;
	private SipServletRequest inboundRequest;

	public ProxyInvite(ProxyListener proxyListener, ProxyPlan proxyPlan) {

		if (proxyPlan != null) {
			this.proxyPlan = new ProxyPlan(proxyPlan); // need a deep copy to manipulate
		} else {
			this.proxyPlan = new ProxyPlan();
		}

		this.proxyListener = proxyListener;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		if (request == null) {
			sipLogger.severe("ProxyInvite.process - Request is null");
			return;
		}

		Boolean doNotProcess;
		this.inboundRequest = request;

		// Call the listener's method to build the ProxyPlan
		if (this.proxyListener != null) {
			this.proxyListener.proxyRequest(inboundRequest, proxyPlan);
		} else {
			sipLogger.severe(request, "ProxyInvite.process - No ProxyListener defined to build proxyPlan");
		}

		// Allow the user to reply with an error code
		doNotProcess = (Boolean) request.getAttribute(ATTR_DO_NOT_PROCESS);
		if (!proxyPlan.isEmpty() && !Boolean.TRUE.equals(doNotProcess)) {

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("ProxyInvite.process - ProxyPlan tiers size=" + proxyPlan.getTiers().size());
			}

			try {

				this.proxyRequest(inboundRequest, proxyPlan, (response) -> {
					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request, "ProxyInvite.process - Got proxy response, status="
								+ response.getStatus() + ", isBranchResponse=" + response.isBranchResponse());
					}

					// this should probably go in 'proxyRequest'
					this.expectRequest(inboundRequest.getApplicationSession(), ACK, (ack) -> {
						if (sipLogger.isLoggable(Level.FINER)) {
							sipLogger.finer(ack, "ProxyInvite.process - expectRequest received ACK, do nothing");
						}
					});

					if (!successful(response) && !response.isBranchResponse()) {
						if (!proxyPlan.isEmpty()) {
							if (sipLogger.isLoggable(Level.FINER)) {
								sipLogger.finer(response,
										"ProxyInvite.process - Calling process again... ProxyPlan tiers: "
												+ proxyPlan.getTiers().size());
							}

							this.process(request);

						}
					}

				});

			} catch (Exception ex) {
				sipLogger.severe(request, "ProxyInvite.process - Exception while attempting to proxy to: "
						+ Logger.serializeObject(proxyPlan));
				sipLogger.severe(request, request.toString());
				sipLogger.severe(request, ex);

				try {
					sendResponse(request.createResponse(RESPONSE_BAD_GATEWAY));
				} catch (Exception ex2) {
					// Unable to send error response - connection may be closed or request invalid
					sipLogger.warning(request, "ProxyInvite.process - Failed to send 502 response: " + ex2.getMessage());
				}

			}

		} else { // doNotProcess
			sipLogger.finer(request, "ProxyInvite.process - empty proxyPlan or request marked as 'doNotProcess'");
		}
	}

}