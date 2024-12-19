package org.vorpal.blade.framework;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SessionKeepAlive.Preference;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipWebSocketContext;

public class DummyMessage implements SipServletMessage, Serializable {
	protected Map<String, Object> attributes = new HashMap<>();
	protected Locale locale;
	protected Map<String, String> headers = new HashMap<>();
	protected Locale acceptLanguage;
	protected String characterEncoding;
	protected Object content;
	protected String contentType;
	protected int contentLength;
	protected Locale contentLanguage;
	protected int expires;
	protected HeaderForm headerForm;
	protected String method;

	@Override
	public void clearAttributes() {
		this.attributes.clear();
	}

	@Override
	public Object getAttribute(String key) {
		return attributes.get(key);
	}

	@Override
	public Set<String> getAttributeNameSet() {
		return attributes.keySet();
	}

	@Override
	public void removeAttribute(String key) {
		attributes.remove(key);
	}

	@Override
	public void setAttribute(String key, Object value) {
		attributes.put(key, value);
	}

	@Override
	public void addAcceptLanguage(Locale locale) {
		this.locale = locale;
	}

	@Override
	public void addAddressHeader(String key, Address value, boolean first) {
		this.headers.put(key, value.toString());
	}

	@Override
	public void addHeader(String key, String value) {
		this.headers.put(key, value);
	}

	@Override
	public void addParameterableHeader(String key, Parameterable value, boolean arg2) {
		this.headers.put(key, value.toString());

	}

	@Override
	public Locale getAcceptLanguage() {
		return this.acceptLanguage;
	}

	@Override
	public Set<Locale> getAcceptLanguageSet() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Iterator<Locale> getAcceptLanguages() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Address getAddressHeader(String key) throws ServletParseException {
		return AsyncSipServlet.sipFactory.createAddress(headers.get(key));
	}

	@Override
	public List<Address> getAddressHeaderList(String key) throws ServletParseException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ListIterator<Address> getAddressHeaders(String arg0) throws ServletParseException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipApplicationSession getApplicationSession() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipApplicationSession getApplicationSession(boolean arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		// TODO Auto-generated method stub
		return java.util.Collections.enumeration(this.attributes.keySet());
	}

	@Override
	public String getCallId() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getCharacterEncoding() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getContent() throws IOException, UnsupportedEncodingException {
		return this.content;
	}

	@Override
	public Locale getContentLanguage() {
		// TODO Auto-generated method stub
		return this.contentLanguage;
	}

	@Override
	public int getContentLength() {

		int contentLength = 0;
		if (content instanceof String) {
			contentLength = ((String) content).length();
		} else if (content instanceof byte[]) {
			contentLength = ((byte[]) content).length;
		}

		return contentLength;
	}

	@Override
	public String getContentType() {
		return this.contentType;
	}

	@Override
	public int getExpires() {
		return this.expires;
	}

	@Override
	public Address getFrom() {
		// TODO Auto-generated method stub
		Address from = null;
		try {
			from = AsyncSipServlet.sipFactory.createAddress(headers.get("From"));
		} catch (Exception e) {
			e.printStackTrace();
		}
		return from;
	}

	@Override
	public String getHeader(String key) {
		return headers.get(key);
	}

	@Override
	public HeaderForm getHeaderForm() {
		return this.headerForm;
	}

	@Override
	public List<String> getHeaderList(String key) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<String> getHeaderNameList() {
		// TODO Auto-generated method stub
		return new ArrayList<String>(headers.keySet());
	}

	@Override
	public Iterator<String> getHeaderNames() {
		// TODO Auto-generated method stub
		return headers.keySet().iterator();
	}

	@Override
	public ListIterator<String> getHeaders(String arg0) {
		// TODO Auto-generated method stub
		return new ArrayList<String>(headers.keySet()).listIterator();
	}

	@Override
	public String getInitialRemoteAddr() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getInitialRemotePort() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getInitialTransport() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getLocalAddr() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getLocalPort() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public SipWebSocketContext getLocalSipWebSocketContext() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getMethod() {
		return this.method;
	}

	@Override
	public Parameterable getParameterableHeader(String key) throws ServletParseException {
		return AsyncSipServlet.sipFactory.createParameterable(headers.get(key));
	}

	@Override
	public List<? extends Parameterable> getParameterableHeaderList(String arg0) throws ServletParseException {
		return null;
	}

	@Override
	public ListIterator<? extends Parameterable> getParameterableHeaders(String arg0) throws ServletParseException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getProtocol() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public byte[] getRawContent() throws IOException {
		// TODO Auto-generated method stub
		return content.toString().getBytes();
	}

	@Override
	public String getRemoteAddr() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getRemotePort() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getRemoteUser() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipSession getSession() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SipSession getSession(boolean arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Preference getSessionKeepAlivePreference() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Address getTo() {
		Address to = null;
		try {
			to = AsyncSipServlet.sipFactory.createAddress(headers.get("To"));
		} catch (Exception e) {
			e.printStackTrace();
		}
		return to;
	}

	@Override
	public String getTransport() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Principal getUserPrincipal() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isCommitted() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isInternallyRouted() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isSecure() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isUserInRole(String arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void pushLocalPath() {
		// TODO Auto-generated method stub

	}

	@Override
	public void pushPath(Address arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void removeHeader(String key) {
		this.headers.remove(key);
	}

	@Override
	public void send() throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setAcceptLanguage(Locale acceptLanguage) {
		this.acceptLanguage = acceptLanguage;
	}

	@Override
	public void setAddressHeader(String key, Address value) {
		this.headers.put(key, value.toString());

	}

	@Override
	public void setCharacterEncoding(String characterEncoding) throws UnsupportedEncodingException {
		this.characterEncoding = characterEncoding;
	}

	@Override
	public void setContent(Object content, String contentType) throws UnsupportedEncodingException {
		this.content = content;
		this.contentType = contentType;

		if (content instanceof String) {
			contentLength = ((String) content).length();
		} else if (content instanceof byte[]) {
			contentLength = ((byte[]) content).length;
		}

	}

	@Override
	public void setContentLanguage(Locale arg0) {
		this.contentLanguage = contentLanguage;
	}

	@Override
	public void setContentLength(int contentLength) {
		this.contentLength = contentLength;
	}

	@Override
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@Override
	public void setExpires(int expires) {
		this.expires = expires;
	}

	@Override
	public void setHeader(String key, String value) {
		this.headers.put(key, value);
	}

	@Override
	public void setHeaderForm(HeaderForm headerForm) {
		this.headerForm = headerForm;
	}

	@Override
	public void setParameterableHeader(String key, Parameterable value) {
		this.headers.put(key, value.toString());
	}

}
