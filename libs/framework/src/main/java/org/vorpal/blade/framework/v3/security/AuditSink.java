package org.vorpal.blade.framework.v3.security;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.events.CloudEvent;

/// Where access records are kept.
///
/// [AccessEvent] publishes a record for every decision, permit and refusal alike,
/// and the bus delivers it. This is the other end: the thing that writes it down
/// so somebody can read it back a year later. Without one, every decision is
/// evaluated, published, and gone.
///
/// ## Why this is not the analytics database
///
/// Analytics records what a call did. This records what a *person* did. They
/// answer to different readers under different retention with different integrity
/// requirements, which is why the framework marks both access types `persist=false`
/// and keeps them off the analytics subscription.
///
/// ## What an implementation owes
///
/// **Append-only, and not editable by its subjects.** An access log that the
/// people in it can revise is not evidence. The property has to come from the
/// store rather than from this code: a bucket retention rule or a database grant
/// of `INSERT` and `SELECT` and nothing else. An implementation that can rewrite
/// its own history satisfies the interface and defeats the purpose.
///
/// **Durable before it acknowledges.** [#write] throwing is the contract for "not
/// stored": the subscription rolls the batch back and redelivers it. Returning
/// normally is a promise that the records survive the process. Swallowing an
/// error here loses audit records silently, which is the one failure this whole
/// path exists to prevent.
///
/// **Longer retention than what it describes.** Access records outlive the
/// recordings they refer to. How much longer is a question for the deployment's
/// counsel, not for this interface.
///
/// ## Discovery
///
/// Found through [ServiceLoader], like the media SPIs beside it. With none
/// installed the subscriber refuses to start rather than run and drop records,
/// because a sink that quietly discards is worse than no sink at all: it looks
/// like compliance.
public interface AuditSink {

	/// Write a batch of access records, durably.
	///
	/// @param batch one or more events in the order received, never empty
	/// @throws IOException if they were not stored. The batch is redelivered, so
	///         an implementation must tolerate seeing the same record twice
	///         rather than assume exactly-once.
	void write(List<CloudEvent> batch) throws IOException;

	/// The installed sink, or null when none is configured.
	///
	/// Only success is cached, for the same reason the media SPIs do it: a static
	/// holding a failed lookup poisons the class for the life of the JVM, so one
	/// bad configuration becomes an outage that survives fixing the configuration.
	static AuditSink installed() {
		AuditSink found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile AuditSink cached;

		private Holder() {
		}

		static AuditSink load() {
			try {
				for (AuditSink found : ServiceLoader.load(AuditSink.class, AuditSink.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(AuditSink.class.getName())
						.log(Level.SEVERE, "an AuditSink provider could not be loaded", t);
			}
			return null;
		}
	}
}
