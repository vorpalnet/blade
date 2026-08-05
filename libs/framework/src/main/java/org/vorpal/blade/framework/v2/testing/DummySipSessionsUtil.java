package org.vorpal.blade.framework.v2.testing;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionsUtil;

/// A mock [SipSessionsUtil] for unit testing. Install it with
/// `Callflow.setSipUtil(new DummySipSessionsUtil())`.
///
/// **A callflow test needs this one.** `Callflow.sendRequest` stamps the Vorpal
/// tracking headers on every initial INVITE, and minting a Vorpal-ID calls
/// `getSipApplicationSessionIds(indexKey)` to check the id is not already taken.
/// Without a `SipSessionsUtil` installed that throws `NullPointerException`,
/// which `sendRequest` catches and converts into a synthetic `500` handed to
/// your callback — so the test sees a plausible-looking failure response
/// instead of an error, and nothing indicates the container stand-in was
/// missing. Install this and the id mints normally.
///
/// Lookups are real, backed by whatever application sessions have been
/// [#register]ed. An unregistered session is not an error: the id-uniqueness
/// check simply comes back empty, which is what a fresh id should do.
public class DummySipSessionsUtil implements SipSessionsUtil {

	private final Map<String, SipApplicationSession> byId = new LinkedHashMap<>();
	private SipApplicationSession current;

	/// Adds an application session to the registry that the lookup methods
	/// search, and makes it the one [#getCurrentApplicationSession] returns.
	public DummySipSessionsUtil register(SipApplicationSession appSession) {
		if (appSession != null) {
			byId.put(appSession.getId(), appSession);
			current = appSession;
		}
		return this;
	}

	/// Sets what [#getCurrentApplicationSession] returns, without changing the
	/// registry.
	public DummySipSessionsUtil setCurrentApplicationSession(SipApplicationSession appSession) {
		current = appSession;
		return this;
	}

	/** {@inheritDoc} */
	@Override
	public SipApplicationSession getApplicationSessionById(String id) {
		return byId.get(id);
	}

	/**
	 * Returns the registered application session whose index keys contain
	 * {@code key}. When {@code create} is true and none matches, a new
	 * {@link DummyApplicationSession} is created, given that index key and
	 * registered — mirroring the container's create-on-demand behaviour.
	 */
	@Override
	public SipApplicationSession getApplicationSessionByKey(String key, boolean create) {
		for (SipApplicationSession appSession : byId.values()) {
			if (appSession.getIndexKeys() != null && appSession.getIndexKeys().contains(key)) {
				return appSession;
			}
		}
		if (create) {
			DummyApplicationSession created = new DummyApplicationSession("test");
			created.addIndexKey(key);
			register(created);
			return created;
		}
		return null;
	}

	/**
	 * Returns the ids of every registered application session carrying this index
	 * key. Empty when none does, which is what lets a freshly minted Vorpal-ID
	 * pass its uniqueness check.
	 */
	@Override
	public Set<String> getSipApplicationSessionIds(String key) {
		Set<String> ids = new LinkedHashSet<>();
		for (Map.Entry<String, SipApplicationSession> e : byId.entrySet()) {
			Set<String> keys = e.getValue().getIndexKeys();
			if (keys != null && keys.contains(key)) {
				ids.add(e.getKey());
			}
		}
		return ids;
	}

	/** {@inheritDoc} */
	@Override
	public Set<String> getSipApplicationSessionIds() {
		return new LinkedHashSet<>(byId.keySet());
	}

	/** {@inheritDoc} */
	@Override
	public SipApplicationSession getCurrentApplicationSession() {
		return current;
	}

	/**
	 * Returns null. The corresponding session belongs to a <em>different
	 * application</em> handling the same call, which has no meaning in a test
	 * running one callflow in isolation.
	 */
	@Override
	public SipSession getCorrespondingSipSession(SipSession session, String headerName) {
		return null;
	}

	/** Stub implementation - returns null. */
	@Override
	public <T> T getManagedBean(Class<T> type, Annotation... qualifiers) {
		return null;
	}
}
