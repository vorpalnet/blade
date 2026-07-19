package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.LinkedList;

/// Default session parameters: 60-minute expiration and **no session selectors**.
///
/// Session selectors are strictly opt-in. Each selector value becomes an OCCAS
/// index key, and on OCCAS 8.3 every index key is a single shared Coherence
/// `CallState.idx` entry holding one element per live session that shares the
/// value, rewritten with a full copy on every add/update/remove. A selector
/// whose value cardinality is low relative to concurrent-call volume (e.g. the
/// From user of a trunk) grows that entry until index updates exceed the
/// cache service's 3-second task timeout, wedging the callstate service
/// cluster-wide. This class previously shipped a From-user selector
/// (`fromSel`) by default; it took down a production-scale cluster and was
/// removed. Configure selectors explicitly in `session.sessionSelectors`,
/// and only on values with per-call cardinality.
public class SessionParametersDefault extends SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public SessionParametersDefault() {
		this.expiration = 60; // 1 hour
		this.keepAlive = null; // deprecated
		this.sessionSelectors = new LinkedList<>();
	}

}
