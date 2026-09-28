package org.vorpal.blade.framework.v3.messaging;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Where a room's messages land, one object each, as the room runs.
///
/// ## Why one object per message
///
/// For the same reason a transcript is one object per utterance
/// ([org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive]): an archive with a retention
/// rule refuses to replace an object, and a conversation grows. Each message is written once under
/// the room's prefix, named by its sequence, and never touched again. A reader lists the prefix to
/// follow the room or to catch up on it.
///
/// ## The sequence continues across the room's life
///
/// A room's application session can expire between meetings while its messages stay. When it
/// opens again it asks [#last] where the numbering stopped, so a new message never takes a stored
/// one's name.
///
/// ## Discovery
///
/// Found through [ServiceLoader]. With no implementation on the classpath the messaging service
/// relays live and stores nothing, and says so once.
public interface MessageArchive {

	/// Store one message. The object is named by its sequence and is never rewritten.
	void append(String room, StoredMessage message) throws IOException;

	/// The room's messages after sequence `after`, in sequence order; `after` 0 reads them all.
	List<StoredMessage> read(String room, int after) throws IOException;

	/// The highest sequence stored for the room, or 0 when it has none.
	int last(String room) throws IOException;

	/// The archive on the classpath, or null.
	static MessageArchive installed() {
		MessageArchive found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile MessageArchive cached;

		private Holder() {
		}

		static MessageArchive load() {
			try {
				for (MessageArchive found : ServiceLoader.load(MessageArchive.class,
						MessageArchive.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				Logger.getLogger(MessageArchive.class.getName())
						.log(Level.SEVERE, "a MessageArchive provider could not be loaded", t);
			}
			return null;
		}
	}
}
