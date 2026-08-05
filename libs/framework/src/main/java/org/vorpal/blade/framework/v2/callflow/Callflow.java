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
import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;
import org.vorpal.blade.framework.v2.proxy.ProxyTier.Mode;

/**
 * The v2 Callflow: the version-neutral baseline
 * {@link org.vorpal.blade.framework.Callflow} plus the v2-only API — the
 * container-proxy overloads ({@code proxyRequest}), {@code continueResponse},
 * every legacy request builder ({@code continueRequest},
 * {@code createNewRequest}, {@code createInitialRequest},
 * {@code createNewInitialRequest}, {@code createContinueInitialRequest},
 * {@code createRequest(SipServletRequest)},
 * {@code createRequest(SipServletResponse, String)}), and the retired name
 * {@code sendAckOrPrack}. These live here, not in the baseline, so the v3
 * Callflow — which extends the baseline directly — never inherits them.
 * Behavior is unchanged from before the baseline hoist.
 * <p>
 * v3 replaces the whole request-builder family with a single method,
 * {@link org.vorpal.blade.framework.v3.Callflow#createRequest(javax.servlet.sip.URI, SipServletRequest)
 * v3.Callflow.createRequest}, and keeps {@code createResponse} for responses
 * plus {@link org.vorpal.blade.framework.Callflow#sendAcknowledgement
 * sendAcknowledgement} / {@link
 * org.vorpal.blade.framework.Callflow#createAcknowledgement
 * createAcknowledgement} for acknowledgements.
 */
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

	/// Answers `destRequest` with a copy of `originResponse` — same status code and
	/// reason phrase, plus the non-system headers and body content that
	/// [org.vorpal.blade.framework.Callflow#copyContentAndHeaders(SipServletResponse,SipServletResponse)]
	/// carries over. A successful INVITE response links the two sessions as a side
	/// effect of the content copy.
	///
	/// Unlike
	/// [org.vorpal.blade.framework.Callflow#createResponse(SipServletRequest,SipServletResponse)],
	/// which returns `null` when either argument is null or the upstream session is
	/// invalid or `TERMINATED`, this method performs no such checks and throws
	/// instead. New code should call `createResponse`; this overload is kept for
	/// existing v2 callflows.
	///
	/// @param destRequest    the request to answer
	/// @param originResponse the response to copy status, headers, and body from
	/// @return the newly created response, never null
	/// @throws ServletParseException        if a header fails to parse
	/// @throws UnsupportedEncodingException if the body's encoding is unsupported
	/// @throws IOException                  if reading the body fails
	public static SipServletResponse continueResponse(SipServletRequest destRequest, SipServletResponse originResponse)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletResponse destResponse = null;
		destResponse = destRequest.createResponse(originResponse.getStatus(), originResponse.getReasonPhrase());

		if (destResponse != null) {
			copyContentAndHeaders(originResponse, destResponse);
		}

		return destResponse;
	}

	// ------------------------------------------------- legacy request builders
	//
	// Superseded by createInitialRequest / createContinueInitialRequest(URI,
	// SipServletRequest) on the baseline, which every current caller uses.
	// They are kept here rather than in the baseline so a v3 callflow is
	// offered exactly one way to clone an inbound request into an outbound
	// one. Each had zero callers across blade, optum, gryphon and bond when
	// it was moved.

	/**
	 * Creates a SipServletRequest from SipFactory by copying the
	 * SipApplicationSession, method, From and To. It also copies the non-system
	 * headers, body content and sets the routing directive to continue. Finally, it
	 * sets the request URI as specified. An INVITE or ACK also links the two
	 * sessions.
	 *
	 * @param uri           the SIP request URI
	 * @param originRequest to be copied
	 * @return the new request
	 * @throws ServletParseException        if a header fails to parse
	 * @throws UnsupportedEncodingException if the body's encoding is unsupported
	 * @throws IOException                  if reading the body fails
	 */
	public static SipServletRequest continueRequest(URI uri, SipServletRequest originRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest destRequest;

		destRequest = sipFactory.createRequest(originRequest.getApplicationSession(), originRequest.getMethod(),
				originRequest.getFrom(), originRequest.getTo());
		destRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, originRequest);
		copyContentAndHeaders(originRequest, destRequest);
		destRequest.setRequestURI(uri);

		return destRequest;
	}

	/**
	 * Creates a SipServletRequest from SipFactory by copying the
	 * SipApplicationSession, method, From and To. It also copies the non-system
	 * headers, body content and sets the routing directive to continue. Finally, it
	 * sets the request URI from the specified String.
	 *
	 * @param strUri        as a Java String
	 * @param originRequest to be copied
	 * @return the new request
	 * @throws ServletParseException        if a header or the URI fails to parse
	 * @throws UnsupportedEncodingException if the body's encoding is unsupported
	 * @throws IOException                  if reading the body fails
	 */
	public static SipServletRequest continueRequest(String strUri, SipServletRequest originRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		return continueRequest(sipFactory.createURI(strUri), originRequest);
	}

	@Deprecated
	public static SipServletRequest createNewRequest(SipServletRequest origin)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				origin.getTo()); //

		copyContentAndHeaders(origin, destination);
		return destination;
	}

	/**
	 * Creates a new SIP request with a different To address, copying content and
	 * headers from the original request.
	 *
	 * @param origin the original SIP request to copy from
	 * @param to     the new destination address
	 * @return the new SIP request
	 * @throws IOException           if an I/O error occurs
	 * @throws ServletParseException if address parsing fails
	 */
	public static SipServletRequest createNewRequest(SipServletRequest origin, Address to)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				to); //

		copyContentAndHeaders(origin, destination);
		return destination;
	}

	/**
	 * Creates a new request with the NEW routing directive by copying the content
	 * and headers from an initial request.
	 * 
	 * @param endpoint
	 * @param initialRequest
	 * @return copy of initial request with the NEW routing directive
	 * @throws ServletParseException
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	public static SipServletRequest createNewInitialRequest(URI endpoint, SipServletRequest initialRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		return createInitialRequest(endpoint, SipApplicationRoutingDirective.NEW, initialRequest);
	}

	/**
	 * Creates a new request with the CONTINUE routing directive using a template
	 * request for content and headers, while using the original request for routing
	 * directive linkage.
	 *
	 * @param endpoint     the destination URI for the request
	 * @param template     the template request to copy content and headers from
	 * @param aliceRequest the original request for routing directive linkage
	 * @return the new SIP request with CONTINUE routing directive
	 * @throws ServletParseException        if parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletRequest createContinueInitialRequest(URI endpoint, SipServletRequest template,
			SipServletRequest aliceRequest) throws ServletParseException, UnsupportedEncodingException, IOException {

		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				template.getApplicationSession(), //
				template.getMethod(), //
				template.getFrom(), //
				template.getTo());

		copyContentAndHeaders(template, bobRequest);

		if (endpoint != null) {
			bobRequest.setRequestURI(copyParameters(template.getRequestURI(), endpoint));
		}

		bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
		return bobRequest;
	}

	/**
	 * Creates a copy of a SIP request, duplicating the method, From, To, content,
	 * headers, and request URI.
	 *
	 * @param aliceRequest the request to copy
	 * @return the new SIP request
	 * @throws ServletParseException        if parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletRequest createRequest(SipServletRequest aliceRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				aliceRequest.getApplicationSession(), //
				aliceRequest.getMethod(), //
				aliceRequest.getFrom(), //
				aliceRequest.getTo());

		copyContentAndHeaders(aliceRequest, bobRequest);
		bobRequest.setRequestURI(aliceRequest.getRequestURI());

		return bobRequest;
	}


	// ------------------------------------------- more legacy request builders
	//
	// Moved off the baseline so v3 offers exactly one way to build a request:
	// Callflow#createRequest(URI, SipServletRequest). Kept here because v2 apps
	// still call them.

	/**
	 * Creates a SipServletRequest on the given session, copying the method,
	 * non-system headers and body content from the origin request. An INVITE or
	 * ACK also links the two sessions.
	 *
	 * @param destSession   the session to create the request on
	 * @param originRequest the request to copy
	 * @return the new request
	 * @throws ServletParseException        if a header fails to parse
	 * @throws UnsupportedEncodingException if the body's encoding is unsupported
	 * @throws IOException                  if reading the body fails
	 */
	public static SipServletRequest continueRequest(SipSession destSession, SipServletRequest originRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest destRequest = destSession.createRequest(originRequest.getMethod());
		copyContentAndHeaders(originRequest, destRequest);
		return destRequest;
	}

	/**
	 * Creates a new SIP request with the specified method on the same session as
	 * the response.
	 *
	 * @param response the SIP response whose session will be used to create the
	 *                 request
	 * @param method   the SIP method for the new request (e.g., "BYE", "INFO")
	 * @return the new SIP request
	 * @throws IOException           if an I/O error occurs
	 * @throws ServletParseException if parsing fails
	 */
	public static SipServletRequest createRequest(SipServletResponse response, String method)
			throws IOException, ServletParseException {
		SipServletRequest request = response.getSession().createRequest(method);
		return request;
	}

	/**
	 * Creates a new request by copying the content and headers from an initial
	 * request.
	 * 
	 * @param endpoint
	 * @param directive
	 * @param initialRequest
	 * @return copy of initial request
	 * @throws ServletParseException
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	public static SipServletRequest createInitialRequest(URI endpoint, SipApplicationRoutingDirective directive,
			SipServletRequest initialRequest) throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				initialRequest.getApplicationSession(), //
				initialRequest.getMethod(), //
				initialRequest.getFrom(), //
				initialRequest.getTo());

		copyContentAndHeaders(initialRequest, bobRequest);

		bobRequest.setRequestURI(copyParameters(initialRequest.getRequestURI(), endpoint));
		bobRequest.setRoutingDirective(directive, initialRequest);

		return bobRequest;
	}

	/**
	 * Creates a new request with the CONTINUE routing directive by copying the
	 * content and headers from an initial request.
	 * 
	 * @param endpoint
	 * @param initialRequest
	 * @return copy of initial request with the CONTINUE routing directive
	 * @throws ServletParseException
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	public static SipServletRequest createContinueInitialRequest(URI endpoint, SipServletRequest initialRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		return createInitialRequest(endpoint, SipApplicationRoutingDirective.CONTINUE, initialRequest);
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
