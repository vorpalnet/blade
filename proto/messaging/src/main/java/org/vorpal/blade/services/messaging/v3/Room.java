package org.vorpal.blade.services.messaging.v3;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vorpal.blade.framework.v3.messaging.MessageFormats;

/// One room: who is in it, and where its numbering stands.
///
/// Held as an attribute of the room's application session ([Rooms]), so the container serializes
/// every change to it and replicates it for failover. Plain Java with no SIP in it, so the rules
/// below are tested without a container.
///
/// ## Members are devices, delivery is by address
///
/// One address may be in a room twice: the same person on a laptop and a phone, each a participant
/// of the meeting. The room counts them apart, so one device leaving does not take the person out,
/// yet it delivers to the address once, since the registrar forks a MESSAGE to every device of it.
///
/// ## What each member takes
///
/// Also by address: the formats an address takes, as its application declared them on joining, or
/// as the address itself answered a MESSAGE it could not take (a `415` with `Accept`). Unknown until
/// one or the other says, and then every message is sent as posted.
public class Room implements Serializable {
	private static final long serialVersionUID = 1L;

	/// A member device: the address it posts and receives as, and the name it goes by.
	public static final class Member implements Serializable {
		private static final long serialVersionUID = 1L;

		final String address;
		final String displayName;

		Member(String address, String displayName) {
			this.address = address;
			this.displayName = displayName;
		}
	}

	private final String name;
	private final Map<String, Member> members = new LinkedHashMap<>(); // by participant
	private final Map<String, List<String>> accepts = new LinkedHashMap<>(); // by address
	private int last;

	/// A room whose stored messages end at `last` (0 for a new room).
	public Room(String name, int last) {
		this.name = name;
		this.last = last;
	}

	public String name() {
		return name;
	}

	/// The sequence of the newest message.
	public int last() {
		return last;
	}

	/// Admit a device. Joining again is a no-op, which is what makes a membership event safe to
	/// apply on every node that hears it.
	///
	/// @return true when the device is new here, so it is owed the room's history
	public boolean join(String participant, String address, String displayName) {
		return join(participant, address, displayName, null);
	}

	/// Admit a device that takes the formats `accept`, most preferred first (null or empty: not
	/// said).
	public boolean join(String participant, String address, String displayName, List<String> accept) {
		if (participant == null || address == null) {
			return false;
		}
		if (accept != null && !accept.isEmpty()) {
			learn(address, accept);
		}
		if (members.containsKey(participant)) {
			return false;
		}
		members.put(participant, new Member(address.toLowerCase(), displayName));
		return true;
	}

	/// Remove a device.
	///
	/// @return true when it was here
	public boolean leave(String participant) {
		return participant != null && members.remove(participant) != null;
	}

	public boolean isMember(String address) {
		return address != null && addresses().contains(address.toLowerCase());
	}

	/// The name `address` goes by here, or null.
	public String nameOf(String address) {
		for (Member m : members.values()) {
			if (m.address.equalsIgnoreCase(address)) {
				return m.displayName;
			}
		}
		return null;
	}

	/// Every address in the room, once each.
	public Set<String> addresses() {
		Set<String> out = new LinkedHashSet<>();
		for (Member m : members.values()) {
			out.add(m.address);
		}
		return out;
	}

	public boolean isEmpty() {
		return members.isEmpty();
	}

	/// Whom a post from `from` goes to: everyone else in the room, or only `to` for a private
	/// message. Empty when `to` is not here.
	public List<String> recipients(String from, String to) {
		List<String> out = new ArrayList<>();
		if (to != null) {
			if (isMember(to) && !to.equalsIgnoreCase(from)) {
				out.add(to.toLowerCase());
			}
			return out;
		}
		for (String address : addresses()) {
			if (!address.equalsIgnoreCase(from)) {
				out.add(address);
			}
		}
		return out;
	}

	/// Record that `address` takes the formats `accept`, replacing what was known.
	public void learn(String address, List<String> accept) {
		if (address != null && accept != null && !accept.isEmpty()) {
			accepts.put(address.toLowerCase(), new ArrayList<>(accept));
		}
	}

	/// The formats `address` takes, or null when nobody has said.
	public List<String> acceptOf(String address) {
		return (address == null) ? null : accepts.get(address.toLowerCase());
	}

	/// The format to send `address` a message posted as `original`: `original` itself when they
	/// take it or have not said, a type the room writes when they take one of those and the room
	/// read the text, else null, when they can be sent nothing ([MessageFormats#choose]).
	public String formatFor(String address, String original, boolean readable) {
		return MessageFormats.choose(acceptOf(address), original, readable);
	}

	/// `address` refused a message sent as `tried` and said it takes `accepted` (a `415` with
	/// `Accept`): keep the list, and name the format to send the message again in.
	///
	/// @return the format to retry in, or null when there is none: the answer listed nothing, or
	///         nothing the room can write, or only the format just refused
	public String refused(String address, List<String> accepted, String tried, String original, boolean readable) {
		if (accepted == null || accepted.isEmpty()) {
			return null;
		}
		learn(address, accepted);
		String next = formatFor(address, original, readable);
		return (next == null || MessageFormats.base(next).equals(MessageFormats.base(tried))) ? null : next;
	}

	/// Take the next sequence for a message.
	public int next() {
		return ++last;
	}

	/// Where a newcomer's replay starts: the sequence after which the newest `limit` messages lie.
	public static int replayAfter(int last, int limit) {
		return Math.max(0, last - Math.max(0, limit));
	}

	/// Whether `address` may see a stored message: one to the room, or a private one they sent or
	/// received.
	public static boolean visibleTo(String address, String from, String to) {
		return to == null || to.equalsIgnoreCase(address) || (from != null && from.equalsIgnoreCase(address));
	}
}
