package org.vorpal.blade.framework.v2.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;


/**
 * Callback interface for handling proxy routing decisions.
 * Implement this interface to customize how requests are routed through the proxy.
 */
public interface ProxyListener {

	/**
	 * Called to build or modify the proxy plan for an incoming request.
	 *
	 * @param request the incoming SIP request to proxy
	 * @param proxyPlan the proxy plan to populate with routing tiers
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	void proxyRequest(SipServletRequest request, ProxyPlan proxyPlan) throws ServletException, IOException;

	/**
	 * Called when a response is received from a proxied request.
	 *
	 * @param response the SIP response received
	 * @param proxyPlan the proxy plan used for routing
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	void proxyResponse(SipServletResponse response, ProxyPlan proxyPlan) throws ServletException, IOException;
}
