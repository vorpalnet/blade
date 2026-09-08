package org.vorpal.blade.framework.v3.media.manifest;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Moving a conversation from the scratchpad into the archive.
///
/// Two operations, and the second exists because the first does not always get
/// to run.
///
/// [#commit] is the ordinary close: the call ended, the manifest is complete,
/// and it lands in the archive. [#sweep] is the crash path: a node died and
/// nobody committed, leaving audio in the archive that nothing describes.
///
/// ## A swept conversation is finalised honestly
///
/// The sweeper writes the manifest that the scratchpad holds and marks it
/// incomplete with a reason, rather than either discarding it or presenting it
/// as a clean recording. Both alternatives are worse: dropping it loses the only
/// description of audio that exists, and committing it as complete tells a
/// reviewer a conversation was captured in full when a node died part way
/// through it.
public final class Conversations {

	private static final Logger LOG = Logger.getLogger(Conversations.class.getName());

	/// How long a conversation may sit untouched before a sweeper treats it as
	/// abandoned. Long enough that a slow call is not swept out from under
	/// itself, short enough to run well ahead of any scratchpad expiry.
	public static final Duration DEFAULT_IDLE = Duration.ofMinutes(30);

	private Conversations() {
	}

	/// Close a conversation: stamp it, commit it, and drop the scratchpad copy.
	///
	/// The scratchpad entry is discarded only after the commit succeeds. If the
	/// commit fails the entry stays, which leaves the conversation for [#sweep]
	/// rather than losing it.
	///
	/// @param manifest the conversation to close
	/// @param node     which node is committing, recorded in the manifest
	/// @return the manifest as committed
	public static ConversationManifest commit(ConversationManifest manifest, String node) throws IOException {
		ManifestArchive archive = ManifestArchive.installed();
		if (archive == null) {
			throw new IOException("no ManifestArchive is installed, so " + manifest.getConversation()
					+ " cannot be committed");
		}

		manifest.setFinalizedUtc(Instant.now().toString());
		manifest.setFinalizedBy(node);

		// Say so rather than discovering it at review time. A conversation whose
		// own description fails its checks is exactly the recording somebody
		// will otherwise rely on years later.
		List<ManifestCheck.Problem> problems = ManifestCheck.check(manifest);
		if (ManifestCheck.hasErrors(problems)) {
			manifest.setComplete(false);
			if (manifest.getIncompleteReason() == null) {
				manifest.setIncompleteReason(summarise(problems));
			}
			LOG.warning("conversation " + manifest.getConversation() + " commits with problems: "
					+ summarise(problems));
		}

		archive.commit(manifest);

		ManifestStore store = ManifestStore.installed();
		if (store != null) {
			try {
				store.discard(manifest.getConversation());
			} catch (IOException e) {
				// The record is safe; this only leaves a scratchpad entry the
				// backstop will clear. Losing the call over it would be worse.
				LOG.log(Level.WARNING, "committed " + manifest.getConversation()
						+ " but could not clear its scratchpad entry", e);
			}
		}
		return manifest;
	}

	/// Finalise conversations abandoned by a node that did not come back.
	///
	/// @param idle how long a conversation must have been untouched
	/// @param node which node is doing the sweeping
	/// @return the conversations committed by this sweep
	public static List<String> sweep(Duration idle, String node) throws IOException {
		ManifestStore store = ManifestStore.installed();
		if (store == null) {
			return new ArrayList<>();
		}
		List<String> swept = new ArrayList<>();
		for (String conversation : store.staleSince((idle == null) ? DEFAULT_IDLE : idle)) {
			try {
				ConversationManifest manifest = store.get(conversation);
				if (manifest == null) {
					continue;
				}
				manifest.setComplete(false);
				if (manifest.getIncompleteReason() == null) {
					manifest.setIncompleteReason("abandoned; finalised by a sweep on " + node);
				}
				commit(manifest, node);
				swept.add(conversation);
			} catch (IOException e) {
				// One conversation that cannot be finalised must not stop the
				// rest. It stays in the scratchpad for the next sweep.
				LOG.log(Level.SEVERE, "could not finalise abandoned conversation " + conversation, e);
			}
		}
		return swept;
	}

	private static String summarise(List<ManifestCheck.Problem> problems) {
		StringBuilder text = new StringBuilder();
		for (ManifestCheck.Problem p : problems) {
			if (!p.isError()) {
				continue;
			}
			if (text.length() > 0) {
				text.append("; ");
			}
			text.append(p.code()).append(" at ").append(p.where());
		}
		return text.toString();
	}
}
