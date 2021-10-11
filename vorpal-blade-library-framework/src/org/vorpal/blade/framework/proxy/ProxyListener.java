package org.vorpal.blade.framework.proxy;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface ProxyListener extends Serializable {

	ProxyTier proxyRequest(SipServletRequest request) throws ServletException, IOException;;

	void proxyResponse(SipServletResponse response) throws ServletException, IOException;

//	void proxyInitial(SipServletRequest request, ProxyTier proxyTier);
//	void proxyContinue(SipServletRequest request, ProxyTier proxyTiers);
//	void proxyComplete(SipServletResponse response, results);

}
