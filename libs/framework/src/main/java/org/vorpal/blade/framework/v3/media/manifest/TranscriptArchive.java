package org.vorpal.blade.framework.v3.media.manifest;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Where a transcript's utterances land, one object each, as the call runs.
///
/// ## Why one object per utterance
///
/// The archive is write-once: a bucket with a retention rule refuses to replace
/// an object, and that refusal is what makes a stored recording evidence. A
/// transcript grows for the length of the call, so it cannot be one object any
/// more than the audio can. It is written the way the audio is, as immutable
/// pieces under one prefix, and a reader assembles them in sequence order.
///
/// Two things fall out of that for free. The transcript is live: anything that
/// can list the prefix can follow the conversation while it is still running.
/// And a node that dies mid-call loses nothing already written: every utterance
/// it stored is durable, and the sweep that finalises the manifest describes
/// exactly what is there.
///
/// ## Where it sits
///
/// Beside the manifest, under the conversation's own prefix, so it inherits
/// whatever rule covers the conversation. [TranscriptRef#getObject] names the
/// prefix relative to the conversation.
///
/// ## This is protected content
///
/// Text is easier to leak than audio and just as sensitive. An implementation
/// holds it under the same controls as the recording, and the access decision
/// for reading it back is `phi:transcript`, which is not `phi:play`.
///
/// ## Discovery
///
/// Found through [ServiceLoader], like [ManifestStore]. With no implementation
/// on the classpath utterances are not stored and [org.vorpal.blade.framework.v3.media.MediaCallflow#transcribe]
/// says so, rather than choosing somewhere convenient to put them.
public interface TranscriptArchive {

	/// Store one utterance of a transcript. The object is named by the
	/// utterance's sequence and is never rewritten.
	///
	/// @param conversation the conversation id, as the manifest carries it
	/// @param transcript   the transcript id within the conversation,
	///                     [TranscriptRef#getId]
	/// @param utterance    what was said, with its sequence already assigned
	void append(String conversation, String transcript, Utterance utterance) throws IOException;

	/// The utterances stored for a transcript, in sequence order.
	///
	/// For reading a transcript back and for a reader checking that the archive
	/// holds what the manifest claims: [TranscriptRef#getUtterances] against
	/// this list's size.
	List<Utterance> read(String conversation, String transcript) throws IOException;

	/// The archive on the classpath, or null.
	static TranscriptArchive installed() {
		TranscriptArchive found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile TranscriptArchive cached;

		private Holder() {
		}

		static TranscriptArchive load() {
			try {
				for (TranscriptArchive found : ServiceLoader.load(TranscriptArchive.class,
						TranscriptArchive.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(TranscriptArchive.class.getName())
						.log(Level.SEVERE, "a TranscriptArchive provider could not be loaded", t);
			}
			return null;
		}
	}
}
