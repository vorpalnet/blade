package org.vorpal.blade.services.messaging.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// The room's rules, without a container: who is in, who a post goes to, and what a newcomer is
/// sent.
class RoomTest {

	@Test
	@DisplayName("numbering continues from where the archive stopped")
	void numberingContinues() {
		Room room = new Room("standup", 41);
		assertEquals(42, room.next());
		assertEquals(43, room.next());
		assertEquals(43, room.last());
	}

	@Test
	@DisplayName("joining again is a no-op, so every node may apply the same fact")
	void joinIsIdempotent() {
		Room room = new Room("standup", 0);
		assertTrue(room.join("p1", "Alice@Example.com", "Alice"));
		assertFalse(room.join("p1", "alice@example.com", "Alice"));
		assertTrue(room.isMember("alice@example.com"));
		assertEquals("Alice", room.nameOf("ALICE@example.com"));
	}

	@Test
	@DisplayName("one device leaving does not take out an address still here on another")
	void twoDevicesOneAddress() {
		Room room = new Room("standup", 0);
		room.join("laptop", "alice@example.com", "Alice");
		room.join("phone", "alice@example.com", "Alice");
		room.join("p2", "bob@example.com", "Bob");
		assertEquals(Collections.singletonList("alice@example.com"), room.recipients("bob@example.com", null));
		assertTrue(room.leave("laptop"));
		assertTrue(room.isMember("alice@example.com"));
		assertFalse(room.leave("laptop"));
		room.leave("phone");
		assertFalse(room.isMember("alice@example.com"));
	}

	@Test
	@DisplayName("a post goes to everyone else, never back to the sender")
	void postGoesToTheOthers() {
		Room room = new Room("standup", 0);
		room.join("p1", "alice@example.com", "Alice");
		room.join("p2", "bob@example.com", "Bob");
		room.join("p3", "carol@example.com", "Carol");
		assertEquals(Arrays.asList("bob@example.com", "carol@example.com"),
				room.recipients("alice@example.com", null));
	}

	@Test
	@DisplayName("a private message reaches only its member, and nobody outside the room")
	void privateMessage() {
		Room room = new Room("standup", 0);
		room.join("p1", "alice@example.com", "Alice");
		room.join("p2", "bob@example.com", "Bob");
		assertEquals(Collections.singletonList("bob@example.com"),
				room.recipients("alice@example.com", "Bob@example.com"));
		assertTrue(room.recipients("alice@example.com", "mallory@example.com").isEmpty());
		assertTrue(room.recipients("alice@example.com", "alice@example.com").isEmpty());
	}

	@Test
	@DisplayName("a newcomer is sent the newest messages, up to the limit")
	void replayWindow() {
		assertEquals(0, Room.replayAfter(0, 200));
		assertEquals(0, Room.replayAfter(150, 200));
		assertEquals(50, Room.replayAfter(250, 200));
		assertEquals(250, Room.replayAfter(250, 0));
	}

	@Test
	@DisplayName("history shows a private message only to its two ends")
	void privateHistory() {
		assertTrue(Room.visibleTo("carol@example.com", "alice@example.com", null));
		assertTrue(Room.visibleTo("bob@example.com", "alice@example.com", "bob@example.com"));
		assertTrue(Room.visibleTo("alice@example.com", "alice@example.com", "bob@example.com"));
		assertFalse(Room.visibleTo("carol@example.com", "alice@example.com", "bob@example.com"));
	}

	@Test
	@DisplayName("what an address takes is declared on joining, replaced by what it later says")
	void acceptDeclaredThenLearned() {
		Room room = new Room("standup", 0);
		room.join("p1", "Alice@example.com", "Alice", Arrays.asList("message/cpim", "text/plain"));
		assertEquals(Arrays.asList("message/cpim", "text/plain"), room.acceptOf("alice@example.com"));
		assertEquals("message/cpim", room.formatFor("alice@example.com", "message/cpim", true));
		room.learn("alice@example.com", Collections.singletonList("text/plain"));
		assertEquals("text/plain", room.formatFor("alice@example.com", "message/cpim", true));
	}

	@Test
	@DisplayName("an address nobody has spoken for is sent what was posted")
	void unknownTakesThePostedFormat() {
		Room room = new Room("standup", 0);
		room.join("p1", "bob@example.com", "Bob");
		assertEquals(null, room.acceptOf("bob@example.com"));
		assertEquals("application/vnd.example", room.formatFor("bob@example.com", "application/vnd.example", false));
	}

	@Test
	@DisplayName("a rejoin with no list keeps what was known")
	void emptyDeclarationKeepsTheKnownList() {
		Room room = new Room("standup", 0);
		room.join("p1", "bob@example.com", "Bob", Collections.singletonList("text/plain"));
		room.leave("p1");
		room.join("p2", "bob@example.com", "Bob", Collections.emptyList());
		assertEquals(Collections.singletonList("text/plain"), room.acceptOf("bob@example.com"));
	}

	@Test
	@DisplayName("a 415 teaches the room what the address takes, and names the format to send again in")
	void refusalTeachesAndRetries() {
		Room room = new Room("standup", 0);
		room.join("p1", "sms@example.com", "Gateway");
		assertEquals("text/plain", room.refused("sms@example.com", Collections.singletonList("text/plain"),
				"message/cpim", "message/cpim", true));
		assertEquals(Collections.singletonList("text/plain"), room.acceptOf("sms@example.com"));
		assertEquals("text/plain", room.formatFor("sms@example.com", "message/cpim", true),
				"the next message goes out right the first time");
	}

	@Test
	@DisplayName("no retry when the 415 lists nothing, nothing the room writes, or only what it refused")
	void refusalWithNothingToRetry() {
		Room room = new Room("standup", 0);
		assertEquals(null, room.refused("a@example.com", Collections.emptyList(), "message/cpim", "message/cpim", true));
		assertEquals(null, room.acceptOf("a@example.com"), "an empty Accept teaches nothing");
		assertEquals(null, room.refused("a@example.com", Collections.singletonList("image/png"),
				"message/cpim", "message/cpim", true));
		assertEquals(null, room.refused("b@example.com", Collections.singletonList("message/cpim;charset=utf-8"),
				"message/cpim", "text/plain", true), "never the format just refused");
		assertEquals(null, room.refused("c@example.com", Collections.singletonList("text/plain"),
				"application/vnd.example", "application/vnd.example", false), "unreadable: nothing to write");
	}
}
