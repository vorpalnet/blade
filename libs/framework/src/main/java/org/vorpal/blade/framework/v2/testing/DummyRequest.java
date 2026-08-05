package org.vorpal.blade.framework.v2.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.sip.Address;
import javax.servlet.sip.AuthInfo;
import javax.servlet.sip.B2buaHelper;
import javax.servlet.sip.InviteBranch;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;

/**
 * A mock implementation of SipServletRequest for unit testing. Provides request
 * functionality including method, headers, and URI management without requiring
 * a SIP container.
 *
 * <p>
 * Creates DummyResponse instances when createResponse() is called. Most proxy
 * and routing methods are stub implementations.
 */
public class DummyRequest extends DummyMessage implements SipServletRequest, Serializable {

	private static final long serialVersionUID = 1L;
	private URI requestUri;
	private static final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
	private final String id = "dummy-req-" + counter.incrementAndGet();
	private boolean initial = true;
	private int maxForwards = 70;
	private int maxBreadth = -1;
	private SipApplicationRoutingDirective routingDirective;

	/**
	 * Constructs a DummyRequest with the specified application session and method.
	 *
	 * @param appSession the application session for this request
	 * @param method     the SIP method (e.g., "INVITE", "BYE", "REFER")
	 * @throws ServletParseException if parsing fails
	 */
	public DummyRequest(SipApplicationSession appSession, String method) throws ServletParseException {
		this.setApplicationSession(appSession);
		this.setMethod(method);
	}

	/**
	 * Constructs a DummyRequest with the specified parameters using string
	 * addresses.
	 *
	 * @param appSession the application session for this request
	 * @param method     the SIP method
	 * @param from       the From address as a string
	 * @param to         the To address as a string
	 * @throws ServletParseException if address parsing fails
	 */
	public DummyRequest(SipApplicationSession appSession, String method, String from, String to)
			throws ServletParseException {
		this.setApplicationSession(appSession);
		this.method = method;
		this.setHeader("From", from);
		this.headers.put("To", to);
	}

	/**
	 * Constructs a DummyRequest with the specified parameters using string
	 * addresses.
	 *
	 * @param method the SIP method
	 * @param from   the From address as a string
	 * @param to     the To address as a string
	 * @throws ServletParseException if address parsing fails
	 */
	public DummyRequest(String method, String from, String to) throws ServletParseException {
		this(new DummyApplicationSession("test"), method, from, to);
	}

	/**
	 * Constructs a DummyRequest with the specified parameters using URI objects.
	 *
	 * @param appSession the application session for this request
	 * @param method     the SIP method
	 * @param from       the From URI
	 * @param to         the To URI (also used as the request URI)
	 */
	public DummyRequest(SipApplicationSession appSession, String method, URI from, URI to) {
		this.setApplicationSession(appSession);
		this.method = method;
		this.headers.put("From", from.toString());
		this.headers.put("To", to.toString());
		this.requestUri = to;
	}

	/**
	 * Constructs a DummyRequest with the specified parameters using Address
	 * objects.
	 *
	 * @param appSession the application session for this request
	 * @param method     the SIP method
	 * @param from       the From address
	 * @param to         the To address
	 */
	public DummyRequest(SipApplicationSession appSession, String method, Address from, Address to) {
		this.setApplicationSession(appSession);
		this.method = method;
		this.headers.put("From", from.toString());
		this.headers.put("To", to.toString());
	}

	/**
	 * Constructs a DummyRequest with the specified method and addresses. The
	 * application session should be set separately using
	 * {@link #setApplicationSession(SipApplicationSession)}.
	 *
	 * @param method the SIP method
	 * @param from   the From address
	 * @param to     the To address
	 */
	public DummyRequest(String method, Address from, Address to) {
		this.method = method;
		this.headers.put("From", from.toString());
		this.headers.put("To", to.toString());
	}

	@Override
	public AsyncContext getAsyncContext() {
		return null;
	}

	@Override
	public long getContentLengthLong() {
		return 0;
	}

	@Override
	public DispatcherType getDispatcherType() {
		return null;
	}

	@Override
	public String getLocalName() {
		return null;
	}

	@Override
	public Locale getLocale() {
		return null;
	}

	@Override
	public Enumeration<Locale> getLocales() {
		return null;
	}

	@Override
	public String getParameter(String name) {
		return null;
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		return null;
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return null;
	}

	@Override
	public String[] getParameterValues(String name) {
		return null;
	}

	@Override
	public String getRealPath(String path) {
		return null;
	}

	@Override
	public String getRemoteHost() {
		return null;
	}

	@Override
	public RequestDispatcher getRequestDispatcher(String path) {
		return null;
	}

	@Override
	public String getScheme() {
		return null;
	}

	@Override
	public String getServerName() {
		return null;
	}

	@Override
	public int getServerPort() {
		return 0;
	}

	@Override
	public ServletContext getServletContext() {
		return null;
	}

	@Override
	public boolean isAsyncStarted() {
		return false;
	}

	@Override
	public boolean isAsyncSupported() {
		return false;
	}

	@Override
	public AsyncContext startAsync() throws IllegalStateException {
		return null;
	}

	@Override
	public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
			throws IllegalStateException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void addAuthHeader(SipServletResponse arg0, AuthInfo arg1) {
		// TODO Auto-generated method stub

	}

	@Override
	public void addAuthHeader(SipServletResponse arg0, String arg1, String arg2) {
		// TODO Auto-generated method stub

	}

	@Override
	public SipServletRequest createCancel() {
		try {
			DummyRequest cancel = new DummyRequest(this.getApplicationSession(), "CANCEL");
			cancel.setSession(this.getSession());
			cancel.setRequestURI(this.getRequestURI());
			cancel.setInitial(false);
			return cancel;
		} catch (ServletParseException neverThrownByThisConstructor) {
			throw new IllegalStateException(neverThrownByThisConstructor);
		}
	}

	@Override
	public InviteBranch createInviteBranch() {
		return null;
	}

	@Override
	public SipServletResponse createResponse(int status) {
		return new DummyResponse(this, status);
	}

	@Override
	public SipServletResponse createResponse(int status, String reasonPhrase) {
		return new DummyResponse(this, status, reasonPhrase);
	}

	@Override
	public SipServletResponse getAcknowledgedResponse() {
		return null;
	}

	@Override
	public B2buaHelper getB2buaHelper() {
		return null;
	}

	@Override
	public SipServletResponse getFinalResponse() {
		return null;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public Address getInitialPoppedRoute() {
		return null;
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		return null;
	}

	@Override
	public int getMaxBreadth() {
		return maxBreadth;
	}

	@Override
	public int getMaxForwards() {
		return maxForwards;
	}

	@Override
	public Address getPoppedRoute() {
		return null;
	}

	@Override
	public Proxy getProxy() throws TooManyHopsException {
		return null;
	}

	@Override
	public Proxy getProxy(boolean arg0) throws TooManyHopsException {
		return null;
	}

	@Override
	public BufferedReader getReader() throws IOException {
		return null;
	}

	@Override
	public SipApplicationRoutingRegion getRegion() {
		return null;
	}

	@Override
	public URI getRequestURI() {
		return requestUri;
	}

	@Override
	public SipApplicationRoutingDirective getRoutingDirective() throws IllegalStateException {
		return routingDirective;
	}

	@Override
	public URI getSubscriberURI() {
		return null;
	}

	@Override
	public boolean isInitial() {
		return initial;
	}

	/** Lets a test mark this request as in-dialog; requests start out initial. */
	public void setInitial(boolean initial) {
		this.initial = initial;
	}

	@Override
	public boolean isRequestUriInternal() {
		return false;
	}

	@Override
	public void pushRoute(SipURI arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void pushRoute(Address arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setMaxBreadth(int arg0) {
		this.maxBreadth = arg0;
	}

	@Override
	public void setMaxForwards(int arg0) {
		this.maxForwards = arg0;
	}

	@Override
	public void setRequestURI(URI requestUri) {
		this.requestUri = requestUri;
	}

	@Override
	public void setRoutingDirective(SipApplicationRoutingDirective arg0, SipServletRequest arg1)
			throws IllegalStateException {
		this.routingDirective = arg0;
	}

}
