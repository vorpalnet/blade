/*
 * MIT License
 *
 * Copyright (c) 2021 Vorpal Networks, LLC (https://vorpal.net)
 *  
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;
import org.vorpal.blade.framework.v2.proxy.ProxyTier.Mode;

/// The v2 Callflow: the version-neutral baseline
/// [org.vorpal.blade.framework.Callflow] plus the v2-only API.
///
/// Two things live here rather than in the baseline, so the v3 Callflow — which
/// extends the baseline directly — never inherits them:
///
/// - the container-proxy overloads, [#proxyRequest(Proxy,URI)] and its siblings,
///   which drive `javax.servlet.sip.Proxy` rather than a B2BUA leg. v3 answers
///   the same need with passthru drop-out on
///   [org.vorpal.blade.framework.v3.Callflow#sendRequest], and the proxy API
///   stays here until that is proven in the field.
/// - [#sendAckOrPrack], the retired name for
///   [org.vorpal.blade.framework.Callflow#sendAcknowledgement].
///
/// The legacy request builders that once lived here — `continueRequest`,
/// `createNewRequest`, `createInitialRequest`, `createNewInitialRequest`,
/// `createContinueInitialRequest`, `createRequest`, and `continueResponse` —
/// were removed once nothing called them. v3 does the whole job with one method,
/// [org.vorpal.blade.framework.v3.Callflow#createRequest(javax.servlet.sip.URI,SipServletRequest)],
/// and keeps [org.vorpal.blade.framework.Callflow#createResponse] for responses
/// plus [org.vorpal.blade.framework.Callflow#sendAcknowledgement] /
/// [org.vorpal.blade.framework.Callflow#createAcknowledgement] for
/// acknowledgements.
public abstract class Callflow extends org.vorpal.blade.framework.Callflow {
	private static final long serialVersionUID = 1L;

	/**
	 * Proxies a SIP request to multiple endpoints with a callback for the response.
	 *
	 * @param proxy          the Proxy object from the original request
	 * @param endpoints      the list of URIs to proxy the request to
	 * @param lambdaFunction callback invoked when a response is received
	 * @throws TooManyHopsException if the Max-Forwards header has reached zero
	 */
	public void proxyRequest(Proxy proxy, List<URI> endpoints, Callback<SipServletResponse> lambdaFunction)
			throws TooManyHopsException {
		if (lambdaFunction != null) {
			SipApplicationSession appSession = proxy.getOriginalRequest().getApplicationSession();
			appSession.setAttribute(PROXY_CALLBACK_ + INVITE, lambdaFunction);
			appSession.setAttribute(IS_PROXY_ATTR, Boolean.TRUE);

			for (URI endpoint : endpoints) {
				// jwm - SUPERARROW NEEDS WORK!
				sipLogger.superArrow(Direction.SEND, false, proxy.getOriginalRequest(), null,
						this.getClass().getSimpleName(), null);
			}

			proxy.proxyTo(endpoints);
		}
	}

	/**
	 * Proxies a SIP request to multiple endpoints without a response callback.
	 *
	 * @param proxy     the Proxy object from the original request
	 * @param endpoints the list of URIs to proxy the request to
	 * @throws TooManyHopsException if the Max-Forwards header has reached zero
	 */
	public void proxyRequest(Proxy proxy, List<URI> endpoints) throws TooManyHopsException {
		proxyRequest(proxy, endpoints, (response) -> {
			// do nothing;
		});
	}

	/**
	 * Proxies a SIP request to a single endpoint without a response callback.
	 *
	 * @param proxy    the Proxy object from the original request
	 * @param endpoint the URI to proxy the request to
	 * @throws TooManyHopsException if the Max-Forwards header has reached zero
	 */
	public void proxyRequest(Proxy proxy, URI endpoint) throws TooManyHopsException {
		List<URI> endpoints = new LinkedList<>();
		endpoints.add(endpoint);
		proxyRequest(proxy, endpoints, (response) -> {
			// do nothing;
		});
	}

	/**
	 * Proxies a SIP request using a ProxyPlan that defines tiered routing with
	 * parallel or sequential mode for each tier.
	 *
	 * @param inboundRequest the inbound SIP request to proxy
	 * @param proxyPlan      the routing plan containing tiers and endpoints
	 * @param lambdaFunction callback invoked when the final response is received
	 * @throws IOException      if an I/O error occurs
	 * @throws ServletException if a servlet error occurs
	 */
	public void proxyRequest(SipServletRequest inboundRequest, ProxyPlan proxyPlan,
			Callback<SipServletResponse> lambdaFunction) throws IOException, ServletException {

		SipApplicationSession appSession = inboundRequest.getApplicationSession();
		boolean isProxy = Boolean.TRUE.equals((Boolean)appSession.getAttribute(IS_PROXY_ATTR));

		if (!proxyPlan.isEmpty()) {

			Proxy proxy = inboundRequest.getProxy();

			ProxyTier proxyTier = proxyPlan.getTiers().remove(0);

			proxy.setParallel(proxyTier.getMode().equals(Mode.parallel));

			List<URI> endpoints = new LinkedList<>();
			for (URI endpoint : proxyTier.getEndpoints()) {
				endpoints.add(endpoint);
			}
			List<ProxyBranch> proxyBranches = proxy.createProxyBranches(endpoints);

			Integer timeout = proxyTier.getTimeout();
			if (timeout != null && timeout > 0) {
				proxy.setProxyTimeout(timeout);
			}

			if (lambdaFunction != null) {
				inboundRequest.getSession().setAttribute(RESPONSE_CALLBACK_ + inboundRequest.getMethod(),
						lambdaFunction);
			}

			inboundRequest.getApplicationSession().setAttribute(IS_PROXY_ATTR, Boolean.TRUE);

			for (ProxyBranch proxyBranch : proxyBranches) {
				sipLogger.superArrow(Direction.SEND, false, proxyBranch.getRequest(), null,
						this.getClass().getSimpleName(), null);
			}

			proxy.startProxy();

		} else {
			sipLogger.finer(inboundRequest, "#8.99 Callflow.proxyRequest - proxyPlan is empty");
		}

	}

	/**
	 * The former name of
	 * {@link org.vorpal.blade.framework.Callflow#sendAcknowledgement
	 * sendAcknowledgement}, kept so existing v2 callflows keep compiling. It
	 * delegates &mdash; there is one implementation, on the baseline.
	 *
	 * @param origin the upstream ACK or PRACK request
	 * @param dest   the downstream response to acknowledge
	 * @throws IOException      if an I/O error occurs
	 * @throws ServletException if a servlet error occurs
	 * @deprecated use
	 *             {@link org.vorpal.blade.framework.Callflow#sendAcknowledgement
	 *             sendAcknowledgement}
	 */
	@Deprecated
	public void sendAckOrPrack(SipServletRequest origin, SipServletResponse dest)
			throws IOException, ServletException {
		sendAcknowledgement(origin, dest);
	}
}
