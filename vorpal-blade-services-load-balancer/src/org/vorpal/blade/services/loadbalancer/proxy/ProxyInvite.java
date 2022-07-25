package org.vorpal.blade.services.loadbalancer.proxy;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.services.loadbalancer.proxy.ProxyTier.Mode;

public class ProxyInvite extends Callflow {
	private ProxyRule proxyRule;
	private SipServletRequest inboundRequest;
	private ProxyListener proxyListener;

	ProxyInvite(ProxyListener proxyListener, ProxyRule proxyRule) {
		this.proxyListener = proxyListener;
		this.proxyRule = new ProxyRule(proxyRule);

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		this.inboundRequest = request;

		if (proxyRule.getTiers().size() > 0) {
			ProxyTier proxyTier = proxyRule.getTiers().remove(0);

			if (proxyTier.getMode().equals(Mode.serial)) {
				ProxySerial proxySerial = new ProxySerial(proxyListener, proxyTier);
				proxySerial.process(request);
			} else {
				ProxyParallel proxyParallel = new ProxyParallel(proxyListener, proxyTier);
				proxyParallel.process(request);
			}
		}

	}

//	@Override
//	public void proxyResponse(SipServletResponse bobResponse, List<SipServletResponse> proxyResponses)
//			throws ServletException, IOException {
//
//		if (proxyRule.getTiers().size() > 0) {
//			this.process(inboundRequest);
//		} else {
//
//			SipServletResponse aliceResponse = this.createResponse(inboundRequest, bobResponse, true);
//
//			sendResponse(aliceResponse, (aliceAck) -> {
//				if (aliceAck.getMethod().equals(PRACK)) {
//					SipServletRequest bobPrack = copyContentAndHeaders(aliceAck, bobResponse.createPrack());
//					sendRequest(bobPrack, (prackResponse) -> {
//						sendResponse(aliceAck.createResponse(prackResponse.getStatus()));
//					});
//				} else if (aliceAck.getMethod().equals(ACK)) {
//					SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
//					sendRequest(bobAck);
//				} else {
//					// implement GLARE here?
//				}
//			});
//
//		}
//
//	}

}
