package org.vorpal.blade.framework.sip;

import javax.servlet.sip.Address;
import javax.servlet.sip.AuthInfo;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

/**
 * A mock implementation of SipFactory for unit testing. Install it with
 * {@code Callflow.setSipFactory(new DetachedSipFactory())} so code that builds
 * requests through the factory — {@code v3.Callflow.createRequest} taking the
 * initial-request path, for one — can run without a SIP container.
 *
 * <p>The {@code createRequest} methods are functional: each returns a
 * {@link DetachedRequest} carrying the From and To it was given, on a fresh
 * {@link DetachedSipSession} of the supplied application session, which is what a
 * container does. Like the container, they refuse ACK and CANCEL — each has to
 * be derived from the message it answers, not created fresh.
 *
 * <p>The URI and Address methods are real too, returning {@link DetachedSipURI}
 * and {@link DetachedAddress}, both of which parse and render properly — so
 * request-URI manipulation, including the {@code copyParameters} merge, can be
 * exercised here. Only {@code createRequest(SipServletRequest, boolean)} and
 * {@code createAuthInfo()} remain stubs.
 */
public class DetachedSipFactory implements SipFactory {

	/** {@inheritDoc} */
	@Override
	public URI createURI(String uri) throws ServletParseException {
		return new DetachedSipURI(uri);
	}

	/** {@inheritDoc} */
	@Override
	public SipURI createSipURI(String user, String host) {
		return new DetachedSipURI("sip:" + (user != null ? user + "@" : "") + host);
	}

	/** {@inheritDoc} */
	@Override
	public Address createAddress(String address) throws ServletParseException {
		return new DetachedAddress(address);
	}

	/** {@inheritDoc} */
	@Override
	public Address createAddress(URI uri) {
		return new DetachedAddress(uri);
	}

	/** {@inheritDoc} */
	@Override
	public Address createAddress(URI uri, String displayName) {
		DetachedAddress address = new DetachedAddress(uri);
		address.setDisplayName(displayName);
		return address;
	}

	/**
	 * Creates a DetachedRequest on a fresh session of the given application session,
	 * carrying the supplied From and To, with the request URI taken from the To
	 * as a container does. Null addresses are tolerated.
	 */
	@Override
	public SipServletRequest createRequest(SipApplicationSession appSession, String method, Address from, Address to) {
		rejectDerivedMethods(method);
		try {
			DetachedRequest request = new DetachedRequest(appSession, method);
			if (from != null) {
				request.setHeader("From", from.toString());
			}
			if (to != null) {
				request.setHeader("To", to.toString());
				request.setRequestURI(to.getURI());
			}
			request.setSession(new DetachedSipSession(appSession));
			return request;
		} catch (Exception ex) {
			throw new IllegalStateException("DetachedSipFactory.createRequest failed", ex);
		}
	}

	/** {@inheritDoc} */
	@Override
	public SipServletRequest createRequest(SipApplicationSession appSession, String method, URI from, URI to) {
		return createRequest(appSession, method, from == null ? null : new DetachedAddress(from),
				to == null ? null : new DetachedAddress(to));
	}

	/** {@inheritDoc} */
	@Override
	public SipServletRequest createRequest(SipApplicationSession appSession, String method, String from, String to)
			throws ServletParseException {
		rejectDerivedMethods(method);
		DetachedRequest request = new DetachedRequest(appSession, method, from, to);
		request.setSession(new DetachedSipSession(appSession));
		return request;
	}

	/** Stub implementation - returns null. */
	@Override
	public SipServletRequest createRequest(SipServletRequest origRequest, boolean sameCallId) {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public Parameterable createParameterable(String s) throws ServletParseException {
		return new DetachedAddress(s);
	}

	/** {@inheritDoc} */
	@Override
	public SipApplicationSession createApplicationSession() {
		return new DetachedApplicationSession("test");
	}

	/** {@inheritDoc} */
	@Override
	public SipApplicationSession createApplicationSessionByKey(String key) {
		return new DetachedApplicationSession(key);
	}

	/** Stub implementation - returns null. */
	@Override
	public AuthInfo createAuthInfo() {
		return null;
	}

	/**
	 * ACK and CANCEL cannot come from a factory — each has to be derived from the
	 * message it answers. OCCAS throws IllegalArgumentException with this same
	 * wording; see SipFactoryImpl.
	 */
	private static void rejectDerivedMethods(String method) {
		if ("ACK".equalsIgnoreCase(method) || "CANCEL".equalsIgnoreCase(method)) {
			throw new IllegalArgumentException("Invalid request method: [" + method + "]");
		}
	}
}
