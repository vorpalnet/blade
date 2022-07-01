package org.vorpal.blade.services.loadbalancer.proxy;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.b2bua.B2buaListener;
import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.framework.callflow.Callflow;

public class ProxyParallel extends Callflow implements B2buaListener {
	private ProxyListener proxyListener;
	private ProxyTier proxyTier;
	private SipServletRequest inboundRequest;
	private List<SipServletResponse> proxyResponses = new LinkedList<>();

	private List<SipServletRequest> proxyRequests = new LinkedList<>();
	private List<SipServletResponse> failedResponses = new LinkedList<>();


	public ProxyParallel(ProxyListener proxyListener, SipServletRequest inboundRequest, ProxyTier proxyTier) {
		this.proxyListener = proxyListener;
		this.inboundRequest = inboundRequest;
		this.proxyTier = proxyTier;
	}
	
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		for(URI uri : proxyTier.getEndpoints()) {
			SipServletRequest outboundRequest = this.createInitialRequest(inboundRequest, true);
			outboundRequest.setRequestURI(uri);
			
			InitialInvite initialInvite = new InitialInvite(this);
			proxyRequests.add(request);
			initialInvite.process(request);			
		}
	}
	

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		failedResponses.add(outboundResponse);

		if(proxyRequests.size() == proxyRequests.size()) {
			proxyListener.proxyResponse(outboundResponse, proxyResponses);
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		proxyListener.proxyResponse(outboundResponse, proxyResponses);
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {

	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {

	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {

	}

}
