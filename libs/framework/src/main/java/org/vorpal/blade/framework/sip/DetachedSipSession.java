package org.vorpal.blade.framework.sip;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.AutomaticProcessingListener;
import javax.servlet.sip.Flow;
import javax.servlet.sip.ForkingContext;
import javax.servlet.sip.InviteBranch;
import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/**
 * A mock implementation of SipSession for unit testing.
 * Provides basic attribute storage and session management without
 * requiring a SIP container.
 *
 * <p>Attributes, identity ({@link #getId()}), validity, dialog state, and
 * {@link #createRequest(String)} are fully functional, which is enough for
 * {@code Callflow.getLinkedSession} to walk between two linked legs. The
 * remaining methods are stubs returning null or default values.
 */
public class DetachedSipSession implements SipSession {

	private static final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();

	private SipApplicationSession appSession;
	private final String id;
	private boolean valid = true;
	private State state = State.INITIAL;
	private final long creationTime = System.currentTimeMillis();
	private boolean invalidateWhenReady;
	private javax.servlet.sip.Flow flow;
	private SipServletRequest activeInvite;

	// LinkedHashMap so getAttributeNameSet returns attributes in insertion
	// order — gives tests that snapshot session state a stable iteration.
	private Map<String, Object> attributes = new LinkedHashMap<>();

	/**
	 * Constructs a DetachedSipSession associated with the specified application session.
	 *
	 * @param appSession the parent application session
	 */
	public DetachedSipSession(SipApplicationSession appSession) {
		this.appSession = appSession;
		this.id = "dummy-" + counter.incrementAndGet();
		if (appSession instanceof DetachedApplicationSession) {
			((DetachedApplicationSession) appSession).register(this);
		}
	}

	/** Sets the dialog state reported by {@link #getState()}. */
	public void setState(State state) {
		this.state = state;
	}

	/** {@inheritDoc} */
	@Override
	public void clearAttributes() {
		attributes.clear();
	}

	/** {@inheritDoc} */
	@Override
	public Object getAttribute(String key) {
		return attributes.get(key);
	}

	/** {@inheritDoc} */
	@Override
	public Set<String> getAttributeNameSet() {
		return attributes.keySet();
	}

	/** {@inheritDoc} */
	@Override
	public void removeAttribute(String key) {
		attributes.remove(key);
	}

	/** {@inheritDoc} */
	@Override
	public void setAttribute(String key, Object value) {
		attributes.put(key, value);
	}

	/**
	 * Creates a DetachedRequest for the specified method, attached to this session so
	 * {@code request.getSession()} resolves the way a container-created request
	 * would. Returns null only if construction fails.
	 *
	 * <p>Mirrors the container in refusing ACK and CANCEL — both
	 * {@code SipSession.createRequest} and {@code SipFactory.createRequest} throw
	 * {@code IllegalArgumentException} for those, since each has to be derived from
	 * the message it answers.
	 */
	@Override
	public SipServletRequest createRequest(String method) {
		if ("ACK".equalsIgnoreCase(method) || "CANCEL".equalsIgnoreCase(method)) {
			throw new IllegalArgumentException("Invalid request method: [" + method + "]");
		}
		try {
			DetachedRequest request = new DetachedRequest(this.getApplicationSession(), method);
			request.setSession(this);
			return request;
		} catch (Exception ex) {
			Callflow.getSipLogger()
					.severe("DetachedSipSession.createRequest - " + ex.getClass().getName() + ": " + ex.getMessage());
			Callflow.getSipLogger().severe(ex);
			return null;
		}
	}

	/**
	 * Returns whatever {@link #setActiveInvite(SipServletRequest)} was given,
	 * regardless of UAMode. Enough to exercise the {@code getActiveInvite(UAC)}
	 * then {@code createCancel()} pattern that Terminate and ReferTransfer use.
	 */
	@Override
	public SipServletRequest getActiveInvite(UAMode arg0) {
		return activeInvite;
	}

	/** Sets the request {@link #getActiveInvite(UAMode)} will hand back. */
	public void setActiveInvite(SipServletRequest activeInvite) {
		this.activeInvite = activeInvite;
	}

	/** Stub implementation - returns null. */
	@Override
	public InviteBranch getActiveInviteBranch() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public SipServletRequest getActiveRequest(String arg0) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Collection<SipServletRequest> getActiveRequests(UAMode arg0) {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public SipApplicationSession getApplicationSession() {
		return appSession;
	}

	/** {@inheritDoc} */
	@Override
	public Enumeration<String> getAttributeNames() {
		return java.util.Collections.enumeration(attributes.keySet());
	}

	/** Stub implementation - returns null. */
	@Override
	public String getCallId() {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public long getCreationTime() {
		return creationTime;
	}

	/** {@inheritDoc} */
	@Override
	public Flow getFlow() {
		return flow;
	}

	/** Stub implementation - returns null. */
	@Override
	public ForkingContext getForkingContext() {
		return null;
	}

	/** Returns this session's generated id, unique within the JVM. */
	@Override
	public String getId() {
		return id;
	}

	/** {@inheritDoc} */
	@Override
	public boolean getInvalidateWhenReady() {
		return invalidateWhenReady;
	}

	/** Stub implementation - returns null. */
	@Override
	public SessionKeepAlive getKeepAlive() {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public long getLastAccessedTime() {
		return creationTime;
	}

	/** Stub implementation - returns null. */
	@Override
	public Address getLocalParty() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public SipApplicationRoutingRegion getRegion() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Address getRemoteParty() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public ServletContext getServletContext() {
		return null;
	}

	/** Returns the dialog state; INITIAL unless set by {@link #setState(State)}. */
	@Override
	public State getState() {
		return state;
	}

	/** Stub implementation - returns null. */
	@Override
	public URI getSubscriberURI() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public SipServletResponse getUnacknowledgedProvisionalResponse(String arg0) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Collection<SipServletResponse> getUnacknowledgedProvisionalResponses(UAMode arg0) {
		return null;
	}

	/** Marks the session invalid and moves it to TERMINATED. */
	@Override
	public void invalidate() {
		this.valid = false;
		this.state = State.TERMINATED;
	}

	/** Stub implementation - returns false. */
	@Override
	public boolean isReadyToInvalidate() {
		return false;
	}

	/** True until {@link #invalidate()} is called. */
	@Override
	public boolean isValid() {
		return valid;
	}

	/** {@inheritDoc} */
	@Override
	public void setFlow(Flow arg0) {
		this.flow = arg0;
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setHandler(String arg0) throws ServletException {
	}

	/** {@inheritDoc} */
	@Override
	public void setInvalidateWhenReady(boolean arg0) {
		this.invalidateWhenReady = arg0;
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setOutboundInterface(InetSocketAddress arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setOutboundInterface(InetAddress arg0) {
	}

	/** Moves the session to TERMINATED, as ending the dialog would. */
	@Override
	public void terminateDialog() {
		this.state = State.TERMINATED;
	}

	/** Stub implementation - does nothing. */
	@Override
	public void terminateDialog(AutomaticProcessingListener arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void terminateProxiedDialog() {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void terminateProxiedDialog(AutomaticProcessingListener arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void terminateProxiedDialog(UAMode arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void terminateProxiedDialog(UAMode arg0, AutomaticProcessingListener arg1) {
	}

}
