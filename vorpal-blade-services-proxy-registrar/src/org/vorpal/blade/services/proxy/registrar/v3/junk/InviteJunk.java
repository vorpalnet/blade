package org.vorpal.blade.services.proxy.registrar.v3.junk;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.services.proxy.registrar.v3.Registrar;

public class InviteJunk extends Callflow implements ProxyBranch.Callback {
	private static final long serialVersionUID = -8674273796595225138L;

	public void proxyRequest(Proxy proxy, List<URI> endpoints, Callback<SipServletResponse> lambdaFunction)
			throws TooManyHopsException {
		SipApplicationSession appSession = proxy.getOriginalRequest().getApplicationSession();
		appSession.setAttribute("PROXY_CALLBACK_INVITE", lambdaFunction);
		proxy.proxyTo(endpoints);
	}

	public void proxyRequest(ProxyPlan proxyPlan, Callback<SipServletResponse> lambdaFunction)
			throws IOException, ServletException {

		sipLogger.finer(proxyPlan.request, "invoking Invite.proxyRequest()");

		sipLogger.finer(proxyPlan.request, "4. plan.tiers.size: " + proxyPlan.tiers.size());

		SipServletRequest request = proxyPlan.request;
		SipApplicationSession appSession = request.getApplicationSession();

		appSession.setAttribute("PROXY_CALLBACK_" + request.getMethod(), lambdaFunction);

//		Boolean isProxy = (Boolean) appSession.getAttribute("isProxy");
//		isProxy = (isProxy != null) ? isProxy: false;

//		appSession.removeAttribute("stopProcessingProxy");

		Proxy proxy = request.getProxy();

		// jwm - testing
		proxy.setProxyTimeout(10);

		// get the next proxy tier
		ProxyTier proxyTier = null;
		for (ProxyTier tier : proxyPlan.tiers) {
			sipLogger.finer(proxyPlan.request, "looking for proxyTier with null final response...");
			if (tier.finalResponse == null) {
				proxyTier = tier;
				sipLogger.finer(proxyPlan.request, "found one");
				break;
			}
		}

		if (proxyTier != null) {

			sipLogger.finer(proxyPlan.request, "proxyTier.endpoints.size: " + proxyTier.endpoints.size());

//			List<ProxyBranch> proxyBranches = proxy.createProxyBranches(proxyTier.endpoints);
//			sipLogger.finer(proxyPlan.request, "proxyBranches.size: " + proxyBranches.size());
//			int totalProxyBranches = 0;
//			for (ProxyBranch proxyBranch : proxy.getProxyBranches()) {
//				if (false == proxyBranch.isStarted()) {
//					totalProxyBranches++;
//					sipLogger.superArrow(Direction.SEND, false, proxyBranch.getRequest(), null,
//							this.getClass().getSimpleName(), null);
//				}
//			}
//			sipLogger.finer(proxyPlan.request, "totalProxyBranches: " + totalProxyBranches);

			int totalProxyBranches = 0;
			sipLogger.finer(proxyPlan.request, "1...");
			totalProxyBranches = proxyTier.endpoints.size();

			sipLogger.finer(proxyPlan.request, "2...");
			appSession.setAttribute("isProxy", true);
			sipLogger.finer(proxyPlan.request, "3...");
			appSession.setAttribute("totalProxyBranches", totalProxyBranches);
			sipLogger.finer(proxyPlan.request, "4...");
			appSession.setAttribute("processedProxyBranches", 0);
			sipLogger.finer(proxyPlan.request, "5...");
			proxy.setSupervised(true);
			sipLogger.finer(proxyPlan.request, "6...");
			// proxy.setRecordRoute(false);
			sipLogger.finer(proxyPlan.request, "7...");
			proxy.setParallel(proxyTier.parallel);
			sipLogger.finer(proxyPlan.request, "8...");

			sipLogger.finer(proxyPlan.request, "Calling startProxy...");
//			proxy.startProxy();
			proxy.proxyTo(proxyTier.endpoints);
			;

		}

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

//		SipApplicationSession appSession = request.getApplicationSession();
		SipApplicationSession appSession = sipUtil
				.getApplicationSessionByKey(AsyncSipServlet.getAccountName(request.getRequestURI()), false);
		if (appSession != null) {
			Registrar registrar = (Registrar) appSession.getAttribute("registrar");
			if (registrar != null) {
				List<URI> contacts = registrar.getContacts(request);

				if (contacts != null && contacts.size() > 0) {

					ProxyPlan plan = new ProxyPlan(request);
					plan.request = request;

					ProxyTier pt = plan.createProxyTier();
					pt.endpoints = contacts;

					sipLogger.finer(request, "1. plan.tiers.size: " + plan.tiers.size());

					this.proxyRequest(plan, (response) -> {
						sipLogger.info(response,
								Color.GREEN("Hooray! Bob's Proxy callback, response: " + response.getStatus()));
						sipLogger.finer(request, "2. plan.tiers.size: " + plan.tiers.size());

						for (ProxyTier proxyTier : plan.tiers) {
							if (proxyTier.finalResponse == null) {
								proxyTier.finalResponse = response;
								break;
							}
						}

						if (failure(response)) {

							SipApplicationSession dougAppSession = sipUtil.getApplicationSessionByKey("doug@vorpal.net",
									false);
							Registrar dougRegistrar = (Registrar) dougAppSession.getAttribute("registrar");

							ProxyTier pt2 = plan.createProxyTier();
							for (URI uri : dougRegistrar.getContacts(request)) {
								pt2.addEndpoint(uri);
							}

							sipLogger.finer(response, "calling proxyRequest again!");
							sipLogger.finer(response, "3. plan.tiers.size: " + plan.tiers.size());

							this.proxyRequest(plan, (dougResponse) -> {
								sipLogger.info(response, Color
										.GREEN("Hooray! Doug's Proxy callback, response: " + dougResponse.getStatus()));
							});

						}

					});
				} else {
					sendResponse(request.createResponse(404));
				}
			} else {
				sendResponse(request.createResponse(404));
			}
		} else {
			sendResponse(request.createResponse(404));
		}
	}

	@Override
	public void handle(ProxyBranch proxyBranch) {

		sipLogger.finer(proxyBranch.getRequest(), Color.GREEN("~~~~~~~" + proxyBranch));
		sipLogger.finer(proxyBranch.getRequest(), Color.GREEN("ProxyBranch.handle invoked on... " + proxyBranch));
		sipLogger.finer(proxyBranch.getRequest(), Color.GREEN("~~~~~~~" + proxyBranch));

	}

}
