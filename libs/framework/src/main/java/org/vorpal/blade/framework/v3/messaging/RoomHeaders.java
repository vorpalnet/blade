package org.vorpal.blade.framework.v3.messaging;

/// The headers a messaging room puts on every MESSAGE it sends a member: the envelope the room
/// vouches for, beside a body it never reads.
///
/// Named once here because two applications must agree on them: `proto/messaging`, which
/// writes them, and the WebRTC gateway, which reads them for a browser. A receiver believes them
/// as it believes `P-Asserted-Identity`, only from a trusted hop.
public final class RoomHeaders {

	/// The room.
	public static final String ROOM = "X-Chat-Room";

	/// The sender's address, as the network asserted it to the room.
	public static final String FROM = "X-Chat-From";

	/// The sender's name in the room, when the room knows it.
	public static final String NAME = "X-Chat-Name";

	/// The room's number for the message; also on the room's `200` to a post.
	public static final String SEQ = "X-Chat-Seq";

	/// When the room accepted the message, epoch milliseconds; also on the room's `200` to a post.
	public static final String TIME = "X-Chat-Time";

	/// A private message's one recipient. On a post, it asks for a private message; on a delivered
	/// message, it marks one.
	public static final String TO = "X-Chat-To";

	private RoomHeaders() {
	}
}
