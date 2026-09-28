package org.vorpal.blade.services.messaging.v3;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.messaging.MessageArchive;
import org.vorpal.blade.framework.v3.messaging.StoredMessage;

/// Where a room lives and how its messages are kept.
///
/// ## One application session per room
///
/// [MessagingServlet#sessionKey] keys every MESSAGE by its room, and the membership subscriber
/// finds the same session with [#session]. So a room's every post and every join runs under one
/// lock, in the order the container takes them, on whichever node holds it. That is what makes the
/// sequence a room's own without a singleton anywhere.
///
/// ## The numbering never restarts
///
/// A room's session expires when it goes quiet ([MessagingSettings#getRoomExpiresMinutes]), while
/// its stored messages stay. A room opened again asks the archive where the numbering stopped, so
/// a new message cannot take a stored one's name. If the archive cannot say, the room does not
/// open: a guess could overwrite a message.
final class Rooms {

	/// The application-session attribute holding the [Room].
	static final String ROOM = "org.vorpal.blade.messaging.room";

	/// Stores off the SIP thread: a post is answered and delivered without waiting on the archive.
	private static final ExecutorService STORE = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "messaging-store");
		t.setDaemon(true);
		return t;
	});

	private static volatile boolean warnedNoArchive;

	private Rooms() {
	}

	/// The application-session key for `room`.
	static String key(String room) {
		return "room:" + room;
	}

	/// The room a MESSAGE is posted to: the Request-URI's user.
	static String nameOf(SipServletRequest request) {
		if (request.getRequestURI() instanceof SipURI) {
			String user = ((SipURI) request.getRequestURI()).getUser();
			if (user != null && !user.isEmpty()) {
				return user;
			}
		}
		return null;
	}

	/// The room's application session, created when it has none. Safe from any thread.
	static SipApplicationSession session(String room) {
		return Callflow.getSipUtil().getApplicationSessionByKey(key(room), true);
	}

	/// The room held by `app`, opened when it holds none. Call under the session's lock.
	///
	/// @throws IOException when the archive cannot say where the room's numbering stands
	static Room of(SipApplicationSession app, String room) throws IOException {
		Room held = (Room) app.getAttribute(ROOM);
		if (held == null) {
			MessageArchive archive = archive();
			held = new Room(room, (archive == null) ? 0 : archive.last(room));
		}
		return held;
	}

	/// Write `room` back to its session, so the change replicates, and keep the session alive for
	/// another idle period.
	static void save(SipApplicationSession app, Room room) {
		app.setInvalidateWhenReady(false);
		app.setAttribute(ROOM, room);
		app.setExpires(expiresMinutes());
	}

	/// Store `message`, off this thread. A failure is logged: the message was delivered, and is
	/// missing only from the history a later newcomer is sent.
	static void store(StoredMessage message) {
		MessageArchive archive = archive();
		if (archive == null) {
			return;
		}
		STORE.submit(() -> {
			try {
				archive.append(message.getRoom(), message);
			} catch (Exception e) {
				Callflow.getSipLogger().warning("messaging: message " + message.getSequence() + " in "
						+ message.getRoom() + " was delivered but not stored: " + e);
			}
		});
	}

	/// Run `task` on the store pool: reading the archive must not hold a room's lock.
	static void offLock(Runnable task) {
		STORE.submit(task);
	}

	/// The installed archive, or null, said once.
	static MessageArchive archive() {
		MessageArchive archive = MessageArchive.installed();
		if (archive == null && !warnedNoArchive) {
			warnedNoArchive = true;
			Callflow.getSipLogger().warning("messaging: no MessageArchive is installed; rooms relay live and store "
					+ "nothing, and a newcomer is sent no history");
		}
		return archive;
	}

	private static int expiresMinutes() {
		MessagingSettings cfg = (MessagingServlet.settings == null) ? null : MessagingServlet.settings.getCurrent();
		return (cfg == null) ? 1440 : cfg.getRoomExpiresMinutes();
	}

	static int replayLimit() {
		MessagingSettings cfg = (MessagingServlet.settings == null) ? null : MessagingServlet.settings.getCurrent();
		return (cfg == null) ? 200 : cfg.getReplayLimit();
	}
}
