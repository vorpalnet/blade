package org.vorpal.blade.overload;

import java.util.HashMap;
import java.util.HashSet;

import com.bea.wcp.sip.engine.server.olp.OlpStatsCollector;

/// An overload protection statistics collector that reports the options
/// service's verdict: 1 while [Overload#refusing], otherwise 0.
///
/// The container finds it through `META-INF/services` when the server starts.
/// `sipserver.xml` names it as a collector, and one threshold at 0.5 turns
/// `reject-traffic` on above it and `accept-traffic` on at or below it. All
/// tuning (when to refuse, when to resume, how to smooth) lives in the options
/// service's configuration, where it can change without a restart; this side
/// never changes.
///
/// A real-time threshold asks for the value on the thread that is handling a
/// new request, for each one, so [#getStats] is two field reads and a write.
public final class OverloadCollector implements OlpStatsCollector {

	@Override
	@SuppressWarnings("unchecked")
	public <N extends Number> N getStats(HashMap<String, String> attributes) {
		long now = System.currentTimeMillis();
		Overload.markConsulted(now);
		return (N) Integer.valueOf(Overload.refusing(now) ? 1 : 0);
	}

	/// Takes no attributes.
	@Override
	public HashSet<String> getAttributeNames() {
		return null;
	}

	@Override
	public void activate() {
	}

	@Override
	public void deactivate() {
	}
}
