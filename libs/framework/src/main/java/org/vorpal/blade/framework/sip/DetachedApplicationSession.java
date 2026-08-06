package org.vorpal.blade.framework.sip;

import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;

/**
 * A mock implementation of SipApplicationSession for unit testing.
 * Provides basic attribute storage and session management without
 * requiring a SIP container.
 *
 * <p>Most methods are stub implementations that return null or default values.
 * The attribute-related methods are fully functional for testing attribute storage.
 */
public class DetachedApplicationSession implements SipApplicationSession {
	// LinkedHashMap so getAttributeNameSet returns attributes in insertion
	// order — gives tests that snapshot session state a stable iteration.
	private Map<String, Object> attributes = new LinkedHashMap<>();
	long creationTime = System.currentTimeMillis();
	int expires = 3;
	String appName = "Dummy";

	// Real registry so getSipSession(id) resolves, which is what
	// Callflow.getLinkedSession needs to walk from one leg to the other.
	private final Map<String, SipSession> sipSessions = new LinkedHashMap<>();
	private static final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
	private final String id = "dummy-app-" + counter.incrementAndGet();
	private final Set<String> indexKeys = new java.util.LinkedHashSet<>();
	private boolean valid = true;
	private boolean invalidateWhenReady;
	private long lastAccessedTime = System.currentTimeMillis();

	/** Registers a session so {@link #getSipSession(String)} can find it by id. */
	public void register(SipSession sipSession) {
		if (sipSession != null && sipSession.getId() != null) {
			sipSessions.put(sipSession.getId(), sipSession);
		}
	}

	/**
	 * Constructs a DetachedApplicationSession with the specified application name.
	 *
	 * @param appName the application name for this session
	 */
	public DetachedApplicationSession(String appName) {
		this.appName = appName;
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

	/** {@inheritDoc} */
	@Override
	public void addIndexKey(String arg0) {
		indexKeys.add(arg0);
	}

	/** Stub implementation - does nothing. */
	@Override
	public void encodeURI(URI arg0) {
	}

	/** Stub implementation - returns null. */
	@Override
	public URL encodeURL(URL arg0) {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public String getApplicationName() {
		return appName;
	}

	/** {@inheritDoc} */
	@Override
	public Iterator<String> getAttributeNames() {
		return attributes.keySet().iterator();
	}

	/** {@inheritDoc} */
	@Override
	public long getCreationTime() {
		return creationTime;
	}

	/** {@inheritDoc} */
	@Override
	public long getExpirationTime() {
		return (expires * 1000 * 60) + System.currentTimeMillis();
	}

	/** {@inheritDoc} */
	@Override
	public String getId() {
		return id;
	}

	/** {@inheritDoc} */
	@Override
	public Set<String> getIndexKeys() {
		return indexKeys;
	}

	/** {@inheritDoc} */
	@Override
	public boolean getInvalidateWhenReady() {
		return invalidateWhenReady;
	}

	/** {@inheritDoc} */
	@Override
	public long getLastAccessedTime() {
		return lastAccessedTime;
	}

	/** Stub implementation - returns null. */
	@Override
	public Object getSession(String arg0, Protocol arg1) {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public Set<?> getSessionSet() {
		return new java.util.LinkedHashSet<>(sipSessions.values());
	}

	/** {@inheritDoc} */
	@Override
	public Set<?> getSessionSet(String arg0) {
		return "SIP".equalsIgnoreCase(arg0) ? getSessionSet() : new java.util.LinkedHashSet<>();
	}

	/** {@inheritDoc} */
	@Override
	public Iterator<?> getSessions() {
		return new java.util.ArrayList<>(sipSessions.values()).iterator();
	}

	/** {@inheritDoc} */
	@Override
	public Iterator<?> getSessions(String arg0) {
		return "SIP".equalsIgnoreCase(arg0) ? getSessions() : java.util.Collections.emptyIterator();
	}

	/** {@inheritDoc} */
	@Override
	public SipSession getSipSession(String arg0) {
		return sipSessions.get(arg0);
	}

	/** Stub implementation - returns null. */
	@Override
	public Future getTaskFuture(String arg0) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Set<Future> getTaskFutures() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public ServletTimer getTimer(String arg0) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Collection<ServletTimer> getTimers() {
		return null;
	}

	/** {@inheritDoc} */
	@Override
	public void invalidate() {
		this.valid = false;
		for (SipSession sipSession : sipSessions.values()) {
			if (sipSession.isValid()) {
				sipSession.invalidate();
			}
		}
	}

	/** Stub implementation - returns false. */
	@Override
	public boolean isReadyToInvalidate() {
		return false;
	}

	/** {@inheritDoc} */
	@Override
	public boolean isValid() {
		return valid;
	}

	/** {@inheritDoc} */
	@Override
	public void removeIndexKey(String arg0) {
		indexKeys.remove(arg0);
	}

	/** {@inheritDoc} */
	@Override
	public int setExpires(int minutes) {
		this.expires = minutes;
		return this.expires;
	}

	/** {@inheritDoc} */
	@Override
	public void setInvalidateWhenReady(boolean arg0) {
		this.invalidateWhenReady = arg0;
	}

}
