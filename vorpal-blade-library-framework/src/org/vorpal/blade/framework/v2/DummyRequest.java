package org.vorpal.blade.framework.v2;

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
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;

public class DummyRequest extends DummyMessage implements SipServletRequest, Serializable {

	private static final long serialVersionUID = 1L;
	private String content;
	private String contentType;

	public DummyRequest(String method, String from, String to) {
		this.method = method;
		this.headers.put("From", from);
		this.headers.put("To", to);
	}

	@Override
	public AsyncContext getAsyncContext() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public long getContentLengthLong() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public DispatcherType getDispatcherType() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getLocalName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Locale getLocale() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Enumeration<Locale> getLocales() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getParameter(String name) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Enumeration<String> getParameterNames() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String[] getParameterValues(String name) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getRealPath(String path) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getRemoteHost() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RequestDispatcher getRequestDispatcher(String path) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getScheme() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getServerName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getServerPort() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public ServletContext getServletContext() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isAsyncStarted() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isAsyncSupported() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public AsyncContext startAsync() throws IllegalStateException {
		// TODO Auto-generated method stub
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public InviteBranch createInviteBranch() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipServletResponse createResponse(int arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipServletResponse createResponse(int arg0, String arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipServletResponse getAcknowledgedResponse() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public B2buaHelper getB2buaHelper() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipServletResponse getFinalResponse() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getId() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Address getInitialPoppedRoute() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getMaxBreadth() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxForwards() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public Address getPoppedRoute() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Proxy getProxy() throws TooManyHopsException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Proxy getProxy(boolean arg0) throws TooManyHopsException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BufferedReader getReader() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipApplicationRoutingRegion getRegion() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public URI getRequestURI() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipApplicationRoutingDirective getRoutingDirective() throws IllegalStateException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public URI getSubscriberURI() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isInitial() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isRequestUriInternal() {
		// TODO Auto-generated method stub
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
		// TODO Auto-generated method stub

	}

	@Override
	public void setMaxForwards(int arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setRequestURI(URI arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setRoutingDirective(SipApplicationRoutingDirective arg0, SipServletRequest arg1)
			throws IllegalStateException {
		// TODO Auto-generated method stub

	}

}
