package org.vorpal.blade.framework.v2.testing;

import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
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
public class DummyApplicationSession implements SipApplicationSession {
	private Map<String, Object> attributes = new HashMap<>();
	long creationTime = System.currentTimeMillis();
	int expires = 3;
	String appName = "Dummy";

	/**
	 * Constructs a DummyApplicationSession with the specified application name.
	 *
	 * @param appName the application name for this session
	 */
	public DummyApplicationSession(String appName) {
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

	/** Stub implementation - does nothing. */
	@Override
	public void addIndexKey(String arg0) {
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

	/** Stub implementation - returns null. */
	@Override
	public String getId() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Set<String> getIndexKeys() {
		return null;
	}

	/** Stub implementation - returns false. */
	@Override
	public boolean getInvalidateWhenReady() {
		return false;
	}

	/** Stub implementation - returns 0. */
	@Override
	public long getLastAccessedTime() {
		return 0;
	}

	/** Stub implementation - returns null. */
	@Override
	public Object getSession(String arg0, Protocol arg1) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Set<?> getSessionSet() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Set<?> getSessionSet(String arg0) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Iterator<?> getSessions() {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public Iterator<?> getSessions(String arg0) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public SipSession getSipSession(String arg0) {
		return null;
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
	public void removeIndexKey(String arg0) {
	}

	/** {@inheritDoc} */
	@Override
	public int setExpires(int minutes) {
		this.expires = minutes;
		return this.expires;
	}

	/** Stub implementation - does nothing. */
	@Override
	public void setInvalidateWhenReady(boolean arg0) {
	}

}
