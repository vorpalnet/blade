package org.vorpal.blade.framework;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;

import javax.servlet.ServletOutputStream;
import javax.servlet.sip.Address;
import javax.servlet.sip.InviteBranch;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.Rel100Exception;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SessionKeepAlive.Preference;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipWebSocketContext;

public class DummyResponse implements SipServletResponse, Serializable {

	SipServletRequest request;

	public DummyResponse(SipServletRequest req, int status) {
		this(req, status, ReasonPhrase.getPhrase(status));
	}

	public DummyResponse(SipServletRequest req, int status, String reason) {
		this.request = req;
		this.status = status;
		this.reason = reason;
	}

	@Override
	public void flushBuffer() throws IOException {
	}

	@Override
	public int getBufferSize() {
		return 0;
	}

	@Override
	public String getCharacterEncoding() {
		return request.getCharacterEncoding();
	}

	@Override
	public String getContentType() {
		return null;
	}

	@Override
	public Locale getLocale() {
		return request.getLocale();
	}

	@Override
	public boolean isCommitted() {
		return true;
	}

	@Override
	public void reset() {
	}

	@Override
	public void resetBuffer() {
	}

	@Override
	public void setBufferSize(int arg0) {
	}

	@Override
	public void setCharacterEncoding(String arg0) {
	}

	@Override
	public void setContentLength(int arg0) {
	}

	@Override
	public void setContentLengthLong(long arg0) {
	}

	@Override
	public void setContentType(String arg0) {
	}

	@Override
	public void setLocale(Locale arg0) {
	}

	@Override
	public void addAcceptLanguage(Locale arg0) {
	}

	@Override
	public void addAddressHeader(String arg0, Address arg1, boolean arg2) {
	}

	@Override
	public void addHeader(String arg0, String arg1) {
	}

	@Override
	public void addParameterableHeader(String arg0, Parameterable arg1, boolean arg2) {
	}

	@Override
	public Locale getAcceptLanguage() {
		return request.getAcceptLanguage();
	}

	@Override
	public Set<Locale> getAcceptLanguageSet() {
		return request.getAcceptLanguageSet();
	}

	@Override
	public Iterator<Locale> getAcceptLanguages() {
		return request.getAcceptLanguages();
	}

	@Override
	public Address getAddressHeader(String arg0) throws ServletParseException {
		return request.getAddressHeader(arg0);
	}

	@Override
	public List<Address> getAddressHeaderList(String arg0) throws ServletParseException {
		return request.getAddressHeaderList(arg0);
	}

	@Override
	public ListIterator<Address> getAddressHeaders(String arg0) throws ServletParseException {
		return request.getAddressHeaders(arg0);
	}

	@Override
	public SipApplicationSession getApplicationSession() {
		return request.getApplicationSession();
	}

	@Override
	public SipApplicationSession getApplicationSession(boolean arg0) {
		return request.getApplicationSession(arg0);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		return request.getAttributeNames();
	}

	@Override
	public String getCallId() {
		return request.getCallId();
	}

	@Override
	public Object getContent() throws IOException, UnsupportedEncodingException {
		return null;
	}

	@Override
	public Locale getContentLanguage() {
		return request.getContentLanguage();
	}

	@Override
	public int getContentLength() {
		return 0;
	}

	@Override
	public int getExpires() {
		return request.getExpires();
	}

	@Override
	public Address getFrom() {
		return request.getFrom();
	}

	@Override
	public String getHeader(String arg0) {
		return request.getHeader(arg0);
	}

	@Override
	public HeaderForm getHeaderForm() {
		return request.getHeaderForm();
	}

	@Override
	public List<String> getHeaderList(String arg0) {
		return request.getHeaderList(arg0);
	}

	@Override
	public List<String> getHeaderNameList() {
		return request.getHeaderNameList();
	}

	@Override
	public Iterator<String> getHeaderNames() {
		return request.getHeaderNames();
	}

	@Override
	public ListIterator<String> getHeaders(String arg0) {
		return request.getHeaders(arg0);
	}

	@Override
	public String getInitialRemoteAddr() {
		return request.getInitialRemoteAddr();
	}

	@Override
	public int getInitialRemotePort() {
		return request.getInitialRemotePort();
	}

	@Override
	public String getInitialTransport() {
		return request.getInitialTransport();
	}

	@Override
	public String getLocalAddr() {
		return request.getLocalAddr();
	}

	@Override
	public int getLocalPort() {
		return request.getLocalPort();
	}

	@Override
	public SipWebSocketContext getLocalSipWebSocketContext() {
		return request.getLocalSipWebSocketContext();
	}

	@Override
	public String getMethod() {
		return request.getMethod();
	}

	@Override
	public Parameterable getParameterableHeader(String arg0) throws ServletParseException {
		return request.getParameterableHeader(arg0);
	}

	@Override
	public List<? extends Parameterable> getParameterableHeaderList(String arg0) throws ServletParseException {
		return request.getParameterableHeaderList(arg0);
	}

	@Override
	public ListIterator<? extends Parameterable> getParameterableHeaders(String arg0) throws ServletParseException {
		return request.getParameterableHeaders(arg0);
	}

	@Override
	public String getProtocol() {
		return request.getProtocol();
	}

	@Override
	public byte[] getRawContent() throws IOException {
		return null;
	}

	@Override
	public String getRemoteAddr() {
		return request.getRemoteAddr();
	}

	@Override
	public int getRemotePort() {
		return request.getRemotePort();
	}

	@Override
	public String getRemoteUser() {
		return request.getRemoteUser();
	}

	@Override
	public SipSession getSession() {
		return request.getSession();
	}

	@Override
	public SipSession getSession(boolean arg0) {
		return request.getSession(arg0);
	}

	@Override
	public Preference getSessionKeepAlivePreference() {
		// TODO Auto-generated method stub
		return request.getSessionKeepAlivePreference();
	}

	@Override
	public Address getTo() {
		return request.getTo();
	}

	@Override
	public String getTransport() {
		return request.getTransport();
	}

	@Override
	public Principal getUserPrincipal() {
		return request.getUserPrincipal();
	}

	@Override
	public boolean isInternallyRouted() {
		return request.isInternallyRouted();
	}

	@Override
	public boolean isSecure() {
		return request.isSecure();
	}

	@Override
	public boolean isUserInRole(String arg0) {
		return request.isUserInRole(arg0);
	}

	@Override
	public void pushLocalPath() {
	}

	@Override
	public void pushPath(Address arg0) {
	}

	@Override
	public void removeHeader(String arg0) {
	}

	@Override
	public void setAcceptLanguage(Locale arg0) {
	}

	@Override
	public void setAddressHeader(String arg0, Address arg1) {
	}

	@Override
	public void setContent(Object arg0, String arg1) throws UnsupportedEncodingException {
	}

	@Override
	public void setContentLanguage(Locale arg0) {
	}

	@Override
	public void setExpires(int arg0) {
	}

	@Override
	public void setHeader(String arg0, String arg1) {
	}

	@Override
	public void setHeaderForm(HeaderForm arg0) {
	}

	@Override
	public void setParameterableHeader(String arg0, Parameterable arg1) {
	}

	@Override
	public void clearAttributes() {
	}

	@Override
	public Object getAttribute(String arg0) {
		return request.getAttribute(arg0);
	}

	@Override
	public Set<String> getAttributeNameSet() {
		return request.getAttributeNameSet();
	}

	@Override
	public void removeAttribute(String arg0) {
	}

	@Override
	public void setAttribute(String arg0, Object arg1) {
	}

	@Override
	public SipServletRequest createAck() {
		return null;
	}

	@Override
	public SipServletRequest createPrack() throws Rel100Exception {
		return null;
	}

	@Override
	public Set<String> getChallengeRealmSet() {
		return null;
	}

	@Override
	public Iterator<String> getChallengeRealms() {
		return null;
	}

	@Override
	public InviteBranch getInviteBranch() {
		return null;
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {
		return null;
	}

	@Override
	public String getProvisionalResponseId() {
		return null;
	}

	@Override
	public Proxy getProxy() {
		return null;
	}

	@Override
	public ProxyBranch getProxyBranch() {
		return null;
	}

	@Override
	public String getReasonPhrase() {
		return reason;
	}

	@Override
	public SipServletRequest getRequest() {
		return request;
	}

	@Override
	public int getStatus() {
		return status;
	}

	@Override
	public PrintWriter getWriter() throws IOException {
		return null;
	}

	@Override
	public boolean isBranchResponse() {
		return false;
	}

	@Override
	public boolean isReliableProvisional() {
		return false;
	}

	@Override
	public void send() throws IOException {
	}

	@Override
	public void sendReliably() throws Rel100Exception {
	}

	@Override
	public void setStatus(int status) {
		this.status = status;
		this.reason = ReasonPhrase.getPhrase(status);
	}

	@Override
	public void setStatus(int status, String reasonPhrase) {
		this.status = status;
		this.reason = reasonPhrase;

	}

//	private static final String VERSION = "SIP/2.0 ";
	protected int status;
	protected String reason;
//	private boolean rel100;
//	private boolean isBranchResponse;
//	private boolean isForgedResponse;
//	private SipServletRequest req;
//	private boolean isVirtualBranchResponse;

	public static class ReasonPhrase {

		public ReasonPhrase() {
		}

		public static String getPhrase(int statusCode) {
			switch (statusCode / 100) {
			case 1: // '\001'
				return getProvisionalPhrase(statusCode);

			case 2: // '\002'
				return getSuccessPhrase(statusCode);

			case 3: // '\003'
				return getRedirectionPhrase(statusCode);

			case 4: // '\004'
				return getClientErrorPhrase(statusCode);

			case 5: // '\005'
				return getServerErrorPhrase(statusCode);

			case 6: // '\006'
				return getGlobalFailurePhrase(statusCode);
			}
			throw new IllegalArgumentException(
					(new StringBuilder()).append("Unknown statusCode: ").append(statusCode).toString());
		}

		private static String getProvisionalPhrase(int statusCode) {
			switch (statusCode) {
			case 100: // 'd'
				return "Trying";

			case 180:
				return "Ringing";

			case 181:
				return "Call Is Being Forwarded";

			case 182:
				return "Queued";

			case 183:
				return "Session Progress";
			}
			return "Trying";
		}

		private static String getSuccessPhrase(int statusCode) {
			switch (statusCode) {
			case 200:
				return "OK";

			case 202:
				return "Accepted";
			}
			return "OK";
		}

		private static String getRedirectionPhrase(int statusCode) {
			switch (statusCode) {
			case 300:
				return "Multiple Choices";

			case 301:
				return "Moved Permanently";

			case 302:
				return "Moved Temporarily";

			case 305:
				return "Use Proxy";

			case 380:
				return "Alternative Service";
			}
			return "Multiple Choices";
		}

		private static String getClientErrorPhrase(int statusCode) {
			switch (statusCode) {
			case 400:
				return "Bad Request";

			case 401:
				return "Unauthorized";

			case 402:
				return "Payment Required";

			case 403:
				return "Forbidden";

			case 404:
				return "Not Found";

			case 405:
				return "Method Not Allowed";

			case 406:
				return "Not Acceptable";

			case 407:
				return "Proxy Authentication Required";

			case 408:
				return "Request Timeout";

			case 410:
				return "Gone";

			case 411:
				return "Length Required";

			case 413:
				return "Request Entity Too Large";

			case 414:
				return "Request-URI Too Long";

			case 415:
				return "Unsupported Media Type";

			case 416:
				return "Unsupported URI Scheme";

			case 420:
				return "Bad Extension";

			case 421:
				return "Extension Required";

			case 422:
				return "Session Interval Too Small";

			case 423:
				return "Interval Too Brief";

			case 428:
				return "Use Identity Header";

			case 436:
				return "Bad Identity-Info";

			case 437:
				return "Unsupported Certificate";

			case 438:
				return "Invalid Identity Header";

			case 480:
				return "Temporarily Unavailable";

			case 481:
				return "Call/Transaction Does Not Exist";

			case 482:
				return "Loop Detected";

			case 483:
				return "Too Many Hops";

			case 484:
				return "Address Incomplete";

			case 485:
				return "Ambiguous";

			case 486:
				return "Busy Here";

			case 487:
				return "Request Terminated";

			case 488:
				return "Not Acceptable Here";

			case 489:
				return "Bad Event";

			case 491:
				return "Request Pending";

			case 493:
				return "Undecipherable";

			case 409:
			case 412:
			case 417:
			case 418:
			case 419:
			case 424:
			case 425:
			case 426:
			case 427:
			case 429:
			case 430:
			case 431:
			case 432:
			case 433:
			case 434:
			case 435:
			case 439:
			case 440:
			case 441:
			case 442:
			case 443:
			case 444:
			case 445:
			case 446:
			case 447:
			case 448:
			case 449:
			case 450:
			case 451:
			case 452:
			case 453:
			case 454:
			case 455:
			case 456:
			case 457:
			case 458:
			case 459:
			case 460:
			case 461:
			case 462:
			case 463:
			case 464:
			case 465:
			case 466:
			case 467:
			case 468:
			case 469:
			case 470:
			case 471:
			case 472:
			case 473:
			case 474:
			case 475:
			case 476:
			case 477:
			case 478:
			case 479:
			case 490:
			case 492:
			default:
				return "Bad Request";
			}
		}

		private static String getServerErrorPhrase(int statusCode) {
			switch (statusCode) {
			case 500:
				return "Server Internal Error";

			case 501:
				return "Not Implemented";

			case 502:
				return "Bad Gateway";

			case 503:
				return "Service Unavailable";

			case 504:
				return "Server Time-out";

			case 505:
				return "Version Not Supported";

			case 513:
				return "Message Too Large";

			case 506:
			case 507:
			case 508:
			case 509:
			case 510:
			case 511:
			case 512:
			default:
				return "Server Internal Error";
			}
		}

		private static String getGlobalFailurePhrase(int statusCode) {
			switch (statusCode) {
			case 600:
				return "Busy Everywhere";

			case 603:
				return "Decline";

			case 604:
				return "Does Not Exist Anywhere";

			case 606:
				return "Not Acceptable";

			case 687:
				return "Dialog Terminated";
			}
			return "Busy Everywhere";
		}
	}
}
