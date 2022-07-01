package org.vorpal.blade.services.loadbalancer.proxy;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface ProxyListener {

	public ProxyRule proxyRequest(SipServletRequest outboundRequest) throws ServletException, IOException;
		
	public void proxyResponse(SipServletResponse outboundResponse, List<SipServletResponse> proxyResponses) throws ServletException, IOException;
	
	
	
	

	
}
