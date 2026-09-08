package org.vorpal.blade.framework.v3.media.manifest;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Where a manifest lands when its conversation is over, and never changes.
///
/// The commit in [#commit] is the moment a conversation becomes a record. Before
/// it the conversation is in flight and the scratchpad holds the truth; after it
/// the description is immutable, sits under the same retention rule as the audio
/// it describes, and cannot be revised by whoever reads it later.
///
/// ## Committing once is the whole design
///
/// Write-once storage refuses an overwrite, which is why the manifest is
/// assembled somewhere else and written here exactly once rather than being
/// updated in place as a call progresses. An implementation must reject a second
/// commit for the same conversation rather than quietly replacing the first: a
/// manifest that can be rewritten is a manifest that proves nothing.
///
/// ## What a reader can check
///
/// Because the committed manifest names every expected track and every segment,
/// a reader can verify the archive holds what the manifest claims instead of
/// trusting it. [ManifestCheck] is that check.
public interface ManifestArchive {

	/// Write this conversation's manifest, once.
	///
	/// @throws IOException if the conversation already has a committed manifest,
	///         or the write fails
	void commit(ConversationManifest manifest) throws IOException;

	/// The committed manifest for a conversation, or null.
	ConversationManifest get(String conversation) throws IOException;

	/// Conversations committed on one UTC day, `yyyy/MM/dd`.
	///
	/// A conversation is listed under the day it *started*, so a call running
	/// across midnight stays whole rather than being split by an accident of the
	/// clock. A caller gathering a whole call follows
	/// [ConversationManifest#getCall] rather than assuming one day holds it.
	List<String> list(String day) throws IOException;

	/// The archive on the classpath, or null.
	static ManifestArchive installed() {
		ManifestArchive found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile ManifestArchive cached;

		private Holder() {
		}

		static ManifestArchive load() {
			try {
				for (ManifestArchive found : ServiceLoader.load(ManifestArchive.class,
						ManifestArchive.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(ManifestArchive.class.getName())
						.log(Level.SEVERE, "a ManifestArchive provider could not be loaded", t);
			}
			return null;
		}
	}
}
