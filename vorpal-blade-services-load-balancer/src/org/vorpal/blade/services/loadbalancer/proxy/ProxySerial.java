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

public class ProxySerial extends Callflow implements B2buaListener {
	private ProxyListener proxyListener;
	private ProxyTier proxyTier;
	private SipServletRequest inboundRequest;
	private List<SipServletResponse> proxyResponses = new LinkedList<>();

	public ProxySerial(ProxyListener proxyListener, SipServletRequest inboundRequest, ProxyTier proxyTier) {
		this.proxyListener = proxyListener;
		this.inboundRequest = inboundRequest;
		this.proxyTier = proxyTier;
	}
	
	
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {

		proxyResponses.add(outboundResponse);

		if (proxyTier.getEndpoints().size() > 0) {
			// randomize it!
			URI endpoint = proxyTier.getEndpoints().get(0);

			InitialInvite initialInvite = new InitialInvite(this);
			initialInvite.process(inboundRequest);
		} else {
			proxyListener.proxyResponse(outboundResponse, proxyResponses);
		}

	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		proxyResponses.add(outboundResponse);
		this.proxyListener.proxyResponse(outboundResponse, proxyResponses);
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
