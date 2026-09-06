package org.vorpal.blade.framework.v3.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.events.CloudEvent;

/// Reading the access log back, as the counterpart to [AuditSink].
///
/// Storing access records satisfies the recording half of an audit control. This
/// is the examining half: without it the records exist and nobody can look at
/// them, which is a filing cabinet nobody has a key to.
///
/// ## Separate from the sink, deliberately
///
/// Writing and reading the audit trail are done by different parties under
/// different authority. The sink writes continuously as a consequence of other
/// people's activity; a reader is a named human exercising `phi:audit`, usually
/// rarely and usually because something is being investigated. An implementation
/// may satisfy both, and nothing here requires it.
///
/// ## What a reader is owed
///
/// **Everything in the period, or an error.** A listing that quietly omits
/// records it could not read is worse than one that fails: the omission is
/// invisible and the reader concludes the activity never happened.
///
/// **The record as written.** No summarising and no reformatting on the way out.
/// What a reviewer reads is what the deciding application published at the time.
public interface AuditArchive {

	/// Access records for one UTC day, `yyyy/MM/dd`, oldest first.
	///
	/// A day at a time for the same reason recordings are listed that way: the
	/// trail is unbounded, and a call that can return all of it eventually will,
	/// on a screen belonging to somebody who needed an afternoon.
	///
	/// @return the records, never null; empty when nothing happened that day
	/// @throws IOException if the period could not be read in full. Failing is
	///         the contract: a partial audit listing that looks complete is the
	///         one outcome a reviewer cannot detect.
	List<CloudEvent> list(String day) throws IOException;

	/// The installed archive, or null where nothing reads the trail back.
	///
	/// Only success is cached, as with the other SPIs: a static that remembers a
	/// failed lookup turns one bad configuration into an outage that survives
	/// fixing it.
	static AuditArchive installed() {
		AuditArchive found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile AuditArchive cached;

		private Holder() {
		}

		static AuditArchive load() {
			try {
				for (AuditArchive found : ServiceLoader.load(AuditArchive.class, AuditArchive.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(AuditArchive.class.getName())
						.log(Level.SEVERE, "an AuditArchive provider could not be loaded", t);
			}
			return null;
		}

		static List<CloudEvent> none() {
			return Collections.emptyList();
		}
	}
}
