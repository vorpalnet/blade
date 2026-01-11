package org.vorpal.blade.framework.v2.testing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
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
 * <p>Most methods are stub implementations that return null or default values.
 * The attribute-related methods are fully functional for testing attribute storage.
 */
public class DummySipSession implements SipSession {

	private SipApplicationSession appSession;

	private Map<String, Object> attributes = new HashMap<>();

	/**
	 * Constructs a DummySipSession associated with the specified application session.
	 *
	 * @param appSession the parent application session
	 */
	public DummySipSession(SipApplicationSession appSession) {
		this.appSession = appSession;
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

	/** Creates a DummyRequest for the specified method. Returns null on error. */
	@Override
	public SipServletRequest createRequest(String method) {
		try {
			SipServletRequest request = new DummyRequest(this.getApplicationSession(), method);
		} catch (Exception ex) {
			Callflow.getSipLogger()
					.severe("DummSipSession.createRequest - " + ex.getClass().getName() + ": " + ex.getMessage());
			Callflow.getSipLogger().severe(ex);
		}
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public SipServletRequest getActiveInvite(UAMode arg0) {
		return null;
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

	/** Stub implementation - returns null. */
	@Override
	public SipApplicationSession getApplicationSession() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Enumeration<String> getAttributeNames() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public String getCallId() {
		return null;
	}

	/** Stub implementation - returns 0. */
	@Override
	public long getCreationTime() {
		return 0;
	}

	/** Stub implementation - returns null. */
	@Override
	public Flow getFlow() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public ForkingContext getForkingContext() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public String getId() {
		return null;
	}

	/** Stub implementation - returns false. */
	@Override
	public boolean getInvalidateWhenReady() {
		return false;
	}

	/** Stub implementation - returns null. */
	@Override
	public SessionKeepAlive getKeepAlive() {
		return null;
	}

	/** Stub implementation - returns 0. */
	@Override
	public long getLastAccessedTime() {
		return 0;
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

	/** Stub implementation - returns null. */
	@Override
	public State getState() {
		return null;
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

	/** Stub implementation - does nothing. */
	@Override
	public void invalidate() {
	}

	/** Stub implementation - returns false. */
	@Override
	public boolean isReadyToInvalidate() {
		return false;
	}

	/** Stub implementation - returns false. */
	@Override
	public boolean isValid() {
		return false;
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setFlow(Flow arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setHandler(String arg0) throws ServletException {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setInvalidateWhenReady(boolean arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setOutboundInterface(InetSocketAddress arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setOutboundInterface(InetAddress arg0) {
	}

	/** Stub implementation - does nothing. */
	@Override
	public void terminateDialog() {
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
