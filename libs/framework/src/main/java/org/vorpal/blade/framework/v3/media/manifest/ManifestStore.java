package org.vorpal.blade.framework.v3.media.manifest;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/// The scratchpad: where a manifest lives while its conversation is still up.
///
/// Audio segments are immutable from birth and go straight to the archive. The
/// description is the only thing that changes while a call runs, so it is the
/// only thing that needs somewhere mutable to live. Splitting them that way
/// keeps the bulky half out of the scratchpad entirely: copying segments through
/// it would double both storage and egress for no benefit.
///
/// ## This holds the same material as the archive
///
/// Transcript partials and classification are protected content. A scratchpad is
/// not a lower-sensitivity place, it is the same sensitivity without a retention
/// rule, and an implementation that treats it as scratch space in the ordinary
/// sense has created a leak.
///
/// ## Its lifecycle rule must not become a way to lose records
///
/// An implementation is expected to expire what it holds, or a crashed node
/// leaves entries forever. The rule has to be that an entry is removed once its
/// conversation is committed, with time-based expiry as a backstop measured in
/// days rather than hours. Expiring an uncommitted conversation destroys the
/// only description of audio that is sitting in the archive, and it does it
/// quietly.
///
/// [#staleSince] exists so a sweeper can find those before an expiry does. See
/// [Conversations#sweep].
public interface ManifestStore {

	/// Write or replace the manifest for a conversation in progress.
	void put(ConversationManifest manifest) throws IOException;

	/// The manifest for a conversation in progress, or null.
	ConversationManifest get(String conversation) throws IOException;

	/// Forget a conversation, which an implementation should do only once it is
	/// committed.
	void discard(String conversation) throws IOException;

	/// Conversations untouched for longer than `idle`, oldest first.
	///
	/// These are the ones a node died in the middle of. A sweeper finalises them
	/// from what is there rather than leaving audio in the archive that nothing
	/// describes.
	List<String> staleSince(Duration idle) throws IOException;

	/// The store on the classpath, or null.
	static ManifestStore installed() {
		ManifestStore found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile ManifestStore cached;

		private Holder() {
		}

		static ManifestStore load() {
			try {
				for (ManifestStore found : ServiceLoader.load(ManifestStore.class,
						ManifestStore.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(ManifestStore.class.getName())
						.log(Level.SEVERE, "a ManifestStore provider could not be loaded", t);
			}
			return null;
		}
	}
}
