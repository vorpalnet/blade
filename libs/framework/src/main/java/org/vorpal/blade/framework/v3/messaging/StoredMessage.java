package org.vorpal.blade.framework.v3.messaging;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;

/// One message as a room stores it: the envelope the server vouches for, and the body it never
/// reads.
///
/// ## The body as posted, and its text
///
/// `contentType` and `body` are the MESSAGE's own, stored as they arrived, so a receiver that takes
/// the sender's format gets it byte for byte. `text` is what the room read out of it, when it knows
/// the format: the one form every other format is written from, and what a reader of the archive
/// uses without parsing anyone's format. It is absent for a format the room cannot read, which it
/// then relays only to members that take that format as it is.
///
/// The envelope fields are the room's: `from` is the address the network asserted, never a claim
/// inside the body, and `sequence` and `atMillis` are assigned by the room.
///
/// ## Sequence is the room's
///
/// Numbered from 1 in the order the room accepted them. It names the object in the archive, it
/// is how a page drops a message it already has, and a gap in it is a message that is missing.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredMessage implements Serializable {
	private static final long serialVersionUID = 1L;


	private int sequence;
	private String room;
	private String from;
	private String displayName;
	private String to;
	private long atMillis;
	private String contentType;
	private String body;
	private String text;

	public StoredMessage() {
	}

	public StoredMessage(int sequence, String room, String from, String displayName, String to, long atMillis,
			String contentType, String body) {
		this.sequence = sequence;
		this.room = room;
		this.from = from;
		this.displayName = displayName;
		this.to = to;
		this.atMillis = atMillis;
		this.contentType = contentType;
		this.body = body;
	}

	/// The room's number for this message, from 1.
	public int getSequence() {
		return sequence;
	}

	public void setSequence(int sequence) {
		this.sequence = sequence;
	}

	/// The room it was posted to.
	public String getRoom() {
		return room;
	}

	public void setRoom(String room) {
		this.room = room;
	}

	/// The sender's address, as the network asserted it.
	public String getFrom() {
		return from;
	}

	public void setFrom(String from) {
		this.from = from;
	}

	/// The sender's name as the room knows it; absent when it knows none.
	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	/// The one member a private message was for; absent for a message to the room.
	public String getTo() {
		return to;
	}

	public void setTo(String to) {
		this.to = to;
	}

	/// When the room accepted it, epoch milliseconds.
	public long getAtMillis() {
		return atMillis;
	}

	public void setAtMillis(long atMillis) {
		this.atMillis = atMillis;
	}

	/// The MESSAGE's Content-Type, as sent.
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	/// The MESSAGE's body, as sent.
	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	/// The message's text, read from the body; absent when the room cannot read the format.
	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}
}
