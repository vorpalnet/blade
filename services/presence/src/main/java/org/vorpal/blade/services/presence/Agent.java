package org.vorpal.blade.services.presence;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/// One agent's presence: what their devices published, and what presence itself knows about the
/// calls it gave them. Kept in the agent's own application session, keyed by their address, so
/// every engine reads and changes the same copy under one lock.
///
/// An agent is **Ready** when one of their publications says `open`, presence has not set them Not
/// Ready, and no call of presence's is ringing them or talking to them. Several devices may publish
/// for one agent (RFC 3903 keeps each publication separate); any open one is enough.
///
/// Skills are published too, one tuple per skill ([Pidf]): the agent serves every skill a live
/// publication names, and is Ready for the ones named `open`. Nothing outside the publications says
/// who serves what, so a skill an agent stops publishing, or whose publication expires, is gone.
final class Agent implements Serializable {
	private static final long serialVersionUID = 2L;

	static final String ATTR = "presence.agent";

	/// One device's publication.
	static final class Publication implements Serializable {
		private static final long serialVersionUID = 2L;
		boolean open;
		long expiresAt;
		/// Skill to whether the agent is Ready for it (its tuple's `<basic>`).
		Map<String, Boolean> skills = new LinkedHashMap<>();
	}

	final String address;

	/// By entity tag (SIP-ETag).
	final Map<String, Publication> publications = new HashMap<>();

	/// Set by presence when an offered call went unanswered: Not Ready until the agent next
	/// publishes `open`, as an agent who walked away would expect.
	boolean held;

	/// The call offered to this agent and not yet answered, by application-session id.
	String reservedFor;

	/// The call this agent is on, by application-session id.
	String busyWith;

	/// When the agent last became Ready, in milliseconds. Longest idle is offered first.
	long idleSince;

	/// SUBSCRIBE dialogs watching this agent: SipSession id to when the subscription ends.
	final Map<String, Long> watchers = new LinkedHashMap<>();

	Agent(String address) {
		this.address = address;
	}

	boolean open(long now) {
		for (Publication p : publications.values()) {
			if (p.open && p.expiresAt > now) {
				return true;
			}
		}
		return false;
	}

	boolean ready(long now) {
		return open(now) && !held && reservedFor == null && busyWith == null;
	}

	/// The skills a live publication names, open or not.
	TreeSet<String> skills(long now) {
		TreeSet<String> out = new TreeSet<>();
		for (Publication p : publications.values()) {
			if (p.expiresAt > now) {
				out.addAll(p.skills.keySet());
			}
		}
		return out;
	}

	/// Ready, and a live publication says `open` for `skill`.
	boolean readyFor(String skill, long now) {
		if (!ready(now)) {
			return false;
		}
		for (Publication p : publications.values()) {
			if (p.expiresAt > now && Boolean.TRUE.equals(p.skills.get(skill))) {
				return true;
			}
		}
		return false;
	}

	/// Whether any publication is still live, open or not: an expired one not yet pruned counts for
	/// nothing, so a watcher is told `offline`, not `not-ready`.
	private boolean published(long now) {
		for (Publication p : publications.values()) {
			if (p.expiresAt > now) {
				return true;
			}
		}
		return false;
	}

	/// A word for logs and the PIDF note.
	String status(long now) {
		if (busyWith != null) {
			return "busy";
		}
		if (reservedFor != null) {
			return "ringing";
		}
		if (!open(now)) {
			return published(now) ? "not-ready" : "offline";
		}
		return held ? "not-ready" : "ready";
	}

	void prune(long now) {
		publications.values().removeIf(p -> p.expiresAt <= now);
		for (Iterator<Long> i = watchers.values().iterator(); i.hasNext();) {
			if (i.next() <= now) {
				i.remove();
			}
		}
	}

	/// The latest moment anything here still matters: a publication or a subscription.
	long lastExpiry() {
		long last = 0;
		for (Publication p : publications.values()) {
			last = Math.max(last, p.expiresAt);
		}
		for (Long w : watchers.values()) {
			last = Math.max(last, w);
		}
		return last;
	}
}
