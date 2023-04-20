package org.vorpal.blade.services.proxy.router;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.proxy.ProxyListener;
import org.vorpal.blade.framework.proxy.ProxyPlan;

public class ProxyInvite extends Callflow {
	private static final long serialVersionUID = 1L;
	private ProxyListener proxyListener;
	private ProxyPlan proxyPlan;
	private SipServletRequest inboundRequest;

	public ProxyInvite(ProxyPlan ProxyPlan, ProxyListener proxyListener) {
		this.proxyPlan = new ProxyPlan(ProxyPlan); // need a deep copy to manipulate
		this.proxyListener = proxyListener;
	}

	public ProxyInvite(ProxyPlan ProxyPlan) {
		this.proxyPlan = new ProxyPlan(ProxyPlan); // need a deep copy to manipulate
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		try {
			sipLogger.fine("ProxyPlan id: " + this.proxyPlan.getId());

			this.inboundRequest = request;

			// SipServletRequest templateRequest = this.createRequest(request);
			this.proxyListener.proxyRequest(inboundRequest, proxyPlan);

			this.proxyRequest(inboundRequest, proxyPlan, (response) -> {
				sipLogger.fine(request, "Got proxy response... " + response.getStatus());

				if (!provisional(response) && !successful(response)) {

					if (!proxyPlan.isEmpty()) {
						sipLogger.fine(response, "Calling process again...");
						this.process(request);
					}

				}

			});

//			if (ProxyPlan.getTiers().size() > 0) {
//				ProxyTier proxyTier = ProxyPlan.getTiers().remove(0);
//
//				if (proxyTier.getMode().equals(Mode.serial)) {
//					this.processSerial(templateRequest, proxyTier);
//				} else {
//					this.processParallel(templateRequest, proxyTier);
//				}
//			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}

	}

//	private void processParallel(SipServletRequest aliceRequest, ProxyTier proxyTier)
//			throws ServletException, IOException {
//
//		sendParallel(aliceRequest, proxyTier, (bobResponse) -> {
//
//			// Give the user the chance to repopulate the ProxyPlan.
//			if (ProxyPlan.isEmpty()) {
//				proxyListener.proxyResponse(bobResponse, ProxyPlan);
//			}
//
//			if (ProxyPlan.isEmpty()) {
//
//				SipServletResponse aliceResponse = createResponse(inboundRequest, bobResponse);
//
//				//jwm-testing
//				
//				
//				
////				aliceResponse.setHeader("Contact", bobResponse.getHeader("Contact"));
//
//				sendResponse(aliceResponse, (aliceAckOrPrack) -> {
//
//					this.linkSessions(bobResponse.getSession(), aliceAckOrPrack.getSession());
//
//					SipServletRequest bobAck = createAcknowlegement(bobResponse, aliceAckOrPrack);
//
//					sendRequest(bobAck);
//				});
//			} else {
//				process(aliceRequest);
//			}
//
//		});
//
//	}

//	private void processSerial(SipServletRequest aliceRequest, ProxyTier proxyTier)
//			throws ServletException, IOException {
//
//		sendSerial(aliceRequest, proxyTier, (bobResponse) -> {
//
//			// Give the user the chance to repopulate the ProxyPlan.
//			if (ProxyPlan.isEmpty()) {
//				proxyListener.proxyResponse(bobResponse, ProxyPlan);
//			}
//
//			if (ProxyPlan.isEmpty()) {
//				sendResponse(createResponse(aliceRequest, bobResponse), (aliceAckOrPrack) -> {
//
//					this.linkSessions(bobResponse.getSession(), aliceAckOrPrack.getSession());
//
//					sendRequest(createAcknowlegement(bobResponse, aliceAckOrPrack));
//				});
//			} else {
//				process(aliceRequest);
//			}
//		});
//
//	}

}
