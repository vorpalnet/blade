package org.vorpal.blade.services.player;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.mixer.MediaMixer;

/// One conference room: the shared [MediaSession] (one media-server context — in JSR-309 a mixer and
/// every leg joined to it must belong to the same session) and its [MediaMixer]. Rooms are keyed by
/// the dialed user and live in a node-local registry ([#open]); the first caller opens the room and
/// the last one out closes it ([#leave]).
///
/// Node-local on purpose, like [PlayerServlet#LIVE]: the live 309 objects are not serializable. Two
/// callers to the same room that land on different cluster nodes get two rooms — routing every
/// call for one room to one node is the placement tier's job, not this prototype's.
final class Room {

	private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();

	final String id;
	final MediaSession ms;
	final MediaMixer mixer;
	/// The app session the room's media session is bound to (the opener's) — the one a
	/// refresh reattaches under, and so the one whose reattached session must be released
	/// when the room closes.
	final String ownerAppId;
	private final Set<String> members = ConcurrentHashMap.newKeySet(); // app-session ids

	private Room(String id, MediaSession ms, MediaMixer mixer) {
		this.id = id;
		this.ms = ms;
		this.mixer = mixer;
		Object owner = ms.getAttribute(org.vorpal.blade.framework.v3.media.MediaCallflow.SIP_APP_SESSION_ID);
		this.ownerAppId = (owner == null) ? null : owner.toString();
	}

	/// Where a room gets its [MediaSession] when it opens — the callflow's `createMediaSession(app)`,
	/// so the session is bound to the opening caller.
	interface SessionSource {
		MediaSession create() throws MsControlException;
	}

	/// The room for `id`, opened on first use with a session from `source` and a fresh audio mixer.
	static Room open(String id, SessionSource source) throws MsControlException {
		Room existing = ROOMS.get(id);
		if (existing != null) {
			return existing;
		}
		MediaSession ms = source.create();
		Room fresh = new Room(id, ms, ms.createMediaMixer(MediaMixer.AUDIO));
		existing = ROOMS.putIfAbsent(id, fresh);
		if (existing != null) {
			ms.release(); // lost the race; nothing was anchored yet
			return existing;
		}
		return fresh;
	}

	static Room get(String id) {
		return (id == null) ? null : ROOMS.get(id);
	}

	void join(String appId) {
		members.add(appId);
	}

	/// Drop a member; when the room is empty, release its media session (the mixer and every leg
	/// with it) and forget the room. Returns true if the room closed.
	boolean leave(String appId) {
		members.remove(appId);
		if (!members.isEmpty()) {
			return false;
		}
		if (ROOMS.remove(id, this)) {
			ms.release();
			// After a same-node refresh the room's media lives on under a reattached session.
			org.vorpal.blade.framework.v3.media.MediaCallflow.releaseReattached(ownerAppId);
		}
		return true;
	}

	int size() {
		return members.size();
	}

	/// Release every room on this node (servlet teardown).
	static void closeAll() {
		for (Room r : ROOMS.values()) {
			try {
				r.ms.release();
			} catch (Exception ignore) {
				// best effort
			}
		}
		ROOMS.clear();
	}
}
