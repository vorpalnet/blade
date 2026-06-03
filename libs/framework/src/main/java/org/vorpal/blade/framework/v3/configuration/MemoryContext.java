package org.vorpal.blade.framework.v3.configuration;

import java.util.HashMap;
import java.util.Map;

/// A [Context] backed by an in-memory `Map` instead of the SIP session.
///
/// Used where no `SipSession` / `SipApplicationSession` exists — notably inside
/// a `SipApplicationRouter` (FSMAR), which the container invokes before any
/// session is resolved. The backing map is supplied by the caller so it can be
/// carried across App Router invocations via the JSR-289 `stateInfo` (and
/// replicated across the cluster), letting extracted values accumulate down a
/// call-path.
///
/// Only the backing store (`get`/`put`/`snapshot`) is overridden; the full
/// `${var}` substitution machinery — reserved vars (`${now}`, `${uuid}`),
/// env/sysprop fallback, iterative re-resolution — is inherited from [Context]
/// unchanged, because [Context#resolve] routes through the overridable [#get].
public class MemoryContext extends Context {

	private final Map<String, String> vars;

	/// New context over a fresh empty map.
	public MemoryContext() {
		super(null);
		this.vars = new HashMap<>();
	}

	/// New context over the supplied map — e.g. the map carried in an App
	/// Router's `stateInfo`, so writes persist across hops.
	public MemoryContext(Map<String, String> vars) {
		super(null);
		this.vars = (vars != null) ? vars : new HashMap<>();
	}

	/// The backing map (e.g. to serialize into `stateInfo`).
	public Map<String, String> getVars() {
		return vars;
	}

	@Override
	public String get(String name) {
		return (name != null) ? vars.get(name) : null;
	}

	@Override
	public void put(String name, String value) {
		if (name == null || value == null) {
			return;
		}
		vars.put(name, resolve(value));
	}

	@Override
	public Map<String, String> snapshot() {
		return new HashMap<>(vars);
	}
}
