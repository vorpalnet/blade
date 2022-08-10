package org.vorpal.blade.framework.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;

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

			this.inboundRequest = request;
			this.proxyListener.proxyRequest(inboundRequest, proxyRule);

			sipLogger.fine("proxyRule tiers: " + proxyRule.getTiers().size());

			this.proxyRequest(inboundRequest, proxyRule, (response) -> {
				sipLogger.fine(request, "Got proxy response... status: " + response.getStatus() + ", isBranchResponse: "
						+ response.isBranchResponse());

				if (!successful(response) && !response.isBranchResponse()) {

					if (!proxyRule.isEmpty()) {
						sipLogger.fine(response,
								"Calling process again... proxyRule tiers: " + proxyRule.getTiers().size());
						this.process(request);
					}

				}

			});

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}

	}

}
