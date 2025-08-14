package org.vorpal.blade.framework.v2.proxy;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

public class ProxyInvite extends Callflow {
	private static final long serialVersionUID = 1L;
	private ProxyListener proxyListener;
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
		Boolean doNotProcess;
		this.inboundRequest = request;

		// Call the listener's method to build the ProxyPlan
		if (this.proxyListener != null) {
			this.proxyListener.proxyRequest(inboundRequest, proxyPlan);
		} else {
			sipLogger.severe(request, "No ProxyListener defined.");
		}

		// jwm - allow the user to reply with an error code
		doNotProcess = (Boolean)request.getAttribute("doNotProcess");
		if (doNotProcess==null || doNotProcess==false ) {

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("ProxyPlan tiers: " + proxyPlan.getTiers().size());
			}

			try {

				this.proxyRequest(inboundRequest, proxyPlan, (response) -> {
					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request, "Got proxy response... status: " + response.getStatus()
								+ ", isBranchResponse: " + response.isBranchResponse());
					}

					// this should probably go in 'proxyRequest'
					this.expectRequest(inboundRequest.getApplicationSession(), ACK, (ack) -> {
						if (sipLogger.isLoggable(Level.FINER)) {
							sipLogger.finer(ack, "ProxyInvite.process, expectRequest received ACK, do nothing");
						}
					});

					if (!successful(response) && !response.isBranchResponse()) {
						if (false == proxyPlan.isEmpty()) {
							if (sipLogger.isLoggable(Level.FINER)) {
								sipLogger.finer(response,
										"Calling process again... ProxyPlan tiers: " + proxyPlan.getTiers().size());
							}

							this.process(request);

						}
					}

				});

			} catch (Exception ex) {
				sipLogger.severe(request,
						"Exception while attempting to proxy to: " + Logger.serializeObject(proxyPlan));
				sipLogger.severe(request, request.toString());
				sipLogger.severe(request, ex);

				try {
					sendResponse(request.createResponse(502));
				} catch (Exception ex2) {
					// eat it;
				}

			}

		} // doNotProcess

	}

}