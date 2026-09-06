package org.vorpal.blade.applications.audit;

import java.io.IOException;
import java.util.List;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.security.AuditSink;

/// Hands each batch of access records to the installed [AuditSink].
///
/// Almost nothing, on purpose. The interesting decisions are the ones it does
/// *not* make.
///
/// **It does not filter.** Whatever the subscription delivers is written. A sink
/// that decides which access records are worth keeping is a sink whose omissions
/// nobody can audit.
///
/// **It does not transform.** The record is written as the producer published it,
/// so what a reviewer reads back is what the deciding application said at the
/// time, not this application's summary of it.
///
/// **It does not swallow.** Every failure propagates, which rolls the batch back
/// for redelivery. Catching here would acknowledge records that were never
/// stored, and losing an access record quietly is the single failure this whole
/// path exists to prevent. A sink that is down becomes a queue that grows, which
/// is visible and recoverable; a sink that is down and silent becomes a gap
/// nobody discovers until somebody asks for the audit.
public class AuditRecorder implements EventSubscriber.Handler {

	@Override
	public void handle(List<CloudEvent> batch) throws Exception {
		if (batch == null || batch.isEmpty()) {
			return;
		}
		AuditSink sink = AuditSink.installed();
		if (sink == null) {
			// The subscription refuses to start without one, so this means the sink
			// went away underneath a running subscription. Throwing keeps the
			// records queued instead of discarding them.
			throw new IOException("no AuditSink is installed; " + batch.size()
					+ " access records are being left for redelivery");
		}
		sink.write(batch);
	}
}
