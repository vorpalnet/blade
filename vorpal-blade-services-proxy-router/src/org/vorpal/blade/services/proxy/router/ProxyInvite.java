package org.vorpal.blade.services.proxy.router;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.proxy.ProxyListener;
import org.vorpal.blade.framework.proxy.ProxyRule;

public class ProxyInvite extends Callflow {
	private ProxyListener proxyListener;
	private ProxyRule proxyRule;
	private SipServletRequest inboundRequest;

	public ProxyInvite(ProxyRule proxyRule, ProxyListener proxyListener) {
		this.proxyRule = new ProxyRule(proxyRule); // need a deep copy to manipulate
		this.proxyListener = proxyListener;
	}

	public ProxyInvite(ProxyRule proxyRule) {
		this.proxyRule = new ProxyRule(proxyRule); // need a deep copy to manipulate
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		try {
			sipLogger.fine("proxyRule id: " + this.proxyRule.getId());

			this.inboundRequest = request;

			// SipServletRequest templateRequest = this.createRequest(request);
			this.proxyListener.proxyRequest(inboundRequest, proxyRule);

			this.proxyRequest(inboundRequest, proxyRule, (response) -> {
				sipLogger.fine(request, "Got proxy response... " + response.getStatus());

				if (!provisional(response) && !successful(response)) {

					if (!proxyRule.isEmpty()) {
						sipLogger.fine(response, "Calling process again...");
						this.process(request);
					}

				}

			});

//			if (proxyRule.getTiers().size() > 0) {
//				ProxyTier proxyTier = proxyRule.getTiers().remove(0);
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
//			// Give the user the chance to repopulate the ProxyRule.
//			if (proxyRule.isEmpty()) {
//				proxyListener.proxyResponse(bobResponse, proxyRule);
//			}
//
//			if (proxyRule.isEmpty()) {
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
//			// Give the user the chance to repopulate the ProxyRule.
//			if (proxyRule.isEmpty()) {
//				proxyListener.proxyResponse(bobResponse, proxyRule);
//			}
//
//			if (proxyRule.isEmpty()) {
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
