package org.vorpal.blade.services.loadbalancer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.callflow.Callflow;

public class ProxyCallflow extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		sipFactory.createRequest(request, true);

//		LoadBalancerConfig lbConfig;

//		if(request.isInitial()) {
//			lbConfig = LoadBalancerServlet.settingsManager.getCurrent();
//			proxyBranch = lbConfig.getProxyBranches(request.getRemoteAddr());
//			while (proxyBranch.isEmpty() == false) {
//				ProxyBranch pb = proxyBranch.remove(0);
//				proxy = request.getProxy();
//				proxy.proxyTo(pb);
//			}
//		}

	}

}
