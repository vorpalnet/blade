package org.vorpal.blade.services.loadbalancer.proxy;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.b2bua.B2buaListener;
import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.framework.callflow.Callflow;

public class ProxySerial extends InitialInvite {
	private ProxyListener proxyListener;
	private ProxyTier proxyTier;
	private SipServletRequest aliceRequest;
	private List<SipServletResponse> proxyResponses = new LinkedList<>();

	public ProxySerial(ProxyListener proxyListener, ProxyTier proxyTier) {
		this.proxyListener = proxyListener;
		this.proxyTier = proxyTier;
	}

//	@Override
//	public void process(SipServletRequest request) throws ServletException, IOException {
//		// start the first request
//		this.inboundRequest = request;
//		Callflow initialInvite = new InitialInvite(this);
//		initialInvite.process(request);
//	}
	
	
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			aliceRequest = request;

			SipApplicationSession appSession = aliceRequest.getApplicationSession();

			Address to = aliceRequest.getTo();
			Address from = aliceRequest.getFrom();

			SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, from, to);
			bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
			copyContentAndHeaders(aliceRequest, bobRequest);
			bobRequest.setRequestURI(aliceRequest.getRequestURI());
			linkSessions(aliceRequest.getSession(), bobRequest.getSession());

			// Pull the first endpoint
			URI uri = proxyTier.endpoints.remove(0);
			String value;
			for (String param : bobRequest.getRequestURI().getParameterNameSet()) {
				value = uri.getParameter(param);
				if (value == null) {
					uri.setParameter(param, value);
				}
			}
			bobRequest.setRequestURI(uri);

			sendRequest(bobRequest, (bobResponse) -> {
				
				if (false == aliceRequest.isCommitted()) {
					
					
					if(proxyTier.getEndpoints().size()==0 || !failure(bobResponse)) {
						// if at first you don't succeed...
						setSessionExpiration(bobResponse);
						SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
						copyContentAndHeaders(bobResponse, aliceResponse);
						sendResponse(aliceResponse, (aliceAck) -> {
							if (aliceAck.getMethod().equals(PRACK)) {
								SipServletRequest bobPrack = copyContentAndHeaders(aliceAck, bobResponse.createPrack());
								sendRequest(bobPrack, (prackResponse) -> {
									sendResponse(aliceAck.createResponse(prackResponse.getStatus()));
								});
							} else if (aliceAck.getMethod().equals(ACK)) {
								SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
								sendRequest(bobAck);
							} else {
								// implement GLARE here?
							}
						});
					} else {
						// ... try again!
						this.process(request);
					}
					


				}
			});

		} catch (Exception e) {
			sipLogger.logStackTrace(request, e);
			throw e;
		}

	}
	
	

//	@Override
//	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
//		URI uri = proxyTier.endpoints.remove(0);
//
//		// copy parameters
//		String value;
//		for (String param : outboundRequest.getRequestURI().getParameterNameSet()) {
//			value = uri.getParameter(param);
//			if (value == null) {
//				uri.setParameter(param, value);
//			}
//		}
//
//		outboundRequest.setRequestURI(uri);
//	}
//
//	@Override
//	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
//
//		// proxyResponses.add(outboundResponse);
//
//		if (proxyTier.getEndpoints().size() > 0) {
//			this.process(inboundRequest);
//		} else {
//			proxyListener.proxyResponse(outboundResponse, proxyResponses);
//		}
//
//	}



}
