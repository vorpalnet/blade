package org.vorpal.blade.services.messaging.v3;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@SchemaAbout(
		name = "Messaging",
		tagline = "Chat rooms over SIP MESSAGE",
		description = "Keeps a room's members, numbers every message, stores it, and sends it to the others as a SIP "
				+ "MESSAGE. A meeting's chat is a room. The message body is the senders' business and is passed through "
				+ "untouched; the sender's identity comes from the network, never from the body.")
public class MessagingSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private int replayLimit = 200;
	private int roomExpiresMinutes = 1440;

	@JsonPropertyDescription("How many of a room's newest stored messages a newcomer is sent when they join. 0 sends none.")
	public int getReplayLimit() {
		return replayLimit;
	}

	public void setReplayLimit(int replayLimit) {
		this.replayLimit = Math.max(0, replayLimit);
	}

	@JsonPropertyDescription("How long a room with no activity is kept in memory, in minutes. Its stored messages outlive it; a room opened again continues their numbering.")
	public int getRoomExpiresMinutes() {
		return roomExpiresMinutes;
	}

	public void setRoomExpiresMinutes(int roomExpiresMinutes) {
		this.roomExpiresMinutes = Math.max(1, roomExpiresMinutes);
	}
}
