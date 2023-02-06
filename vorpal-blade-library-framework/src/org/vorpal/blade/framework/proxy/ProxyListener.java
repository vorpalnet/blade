package org.vorpal.blade.framework.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;


public interface ProxyListener {

	public void proxyRequest(SipServletRequest request, ProxyPlan proxyPlan) throws ServletException, IOException;

	public void proxyResponse(SipServletResponse response, ProxyPlan proxyPlan) throws ServletException, IOException;

}
