package org.vorpal.blade.framework.v3.media;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Reading recordings back, as the counterpart to [RecordingDestinations].
///
/// The two are deliberately separate interfaces rather than one. Writing and
/// reading a recording are done by different parties under different authority:
/// the media server writes with a capability that cannot read, and a reviewer
/// reads with an identity that cannot write. An implementation may satisfy both,
/// but nothing in the framework requires it, and a deployment that lets the
/// recorder read its own archive has to say so explicitly.
///
/// ## What this does not decide
///
/// Whether a caller may see any of this. That is
/// `framework.v3.security.AccessEvaluator`, and it is the application's job to
/// ask before calling anything here. This interface is the storage, not the
/// policy: it will hand out whatever it is asked for.
public interface RecordingArchive {

	/// One recording, as a listing shows it.
	///
	/// The attributes are what an access rule matches on: tenant, queue, agent,
	/// classification, whatever the deployment records. They are carried as a map
	/// rather than fields because the framework does not get to decide which
	/// facts a customer's policy turns on.
	final class RecordingSummary {
		private final String id;
		private final long bytes;
		private final boolean complete;
		private final Map<String, String> attributes;

		public RecordingSummary(String id, long bytes, boolean complete, Map<String, String> attributes) {
			this.id = id;
			this.bytes = bytes;
			this.complete = complete;
			this.attributes = attributes;
		}

		public String id() {
			return id;
		}

		public long bytes() {
			return bytes;
		}

		/// False when the recording was truncated: segments dropped, or a call
		/// that ended without the recorder closing cleanly. A reviewer must be
		/// able to tell, because an incomplete recording and a complete one are
		/// different evidence.
		public boolean complete() {
			return complete;
		}

		public Map<String, String> attributes() {
			return attributes;
		}
	}

	/// Recordings for one UTC day, `yyyy/MM/dd`.
	///
	/// A day at a time rather than "everything": an archive is unbounded and a
	/// listing that can return all of it is a listing that will, eventually, on a
	/// screen belonging to someone who only needed one call.
	List<RecordingSummary> list(String day) throws IOException;

	/// Write one recording to `out`, whole, in order.
	///
	/// Streamed through the application rather than handed over as a link. That
	/// is the difference the permission ladder draws between `phi:play` and
	/// `phi:export`: playing keeps the content inside a path that is audited on
	/// every request, and only exporting hands over something that outlives the
	/// decision.
	void writeTo(String recordingId, OutputStream out) throws IOException;

	/// The installed implementation, or null where nothing reads recordings back.
	///
	/// Only success is cached. A failed lookup is retried, because a static that
	/// remembers a failure turns one misconfiguration into an outage that
	/// survives every fix short of a restart.
	static RecordingArchive installed() {
		RecordingArchive found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile RecordingArchive cached;

		private Holder() {
		}

		static RecordingArchive load() {
			try {
				for (RecordingArchive found : ServiceLoader.load(RecordingArchive.class,
						RecordingArchive.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(RecordingArchive.class.getName())
						.log(Level.SEVERE, "a RecordingArchive provider could not be loaded", t);
			}
			return null;
		}
	}
}
