package org.vorpal.blade.framework.v3.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Reading, writing and choosing formats: what a member is sent for what was posted.
class MessageFormatsTest {

	private static final String CPIM_BODY = "From: \"Mallory\" <sip:mallory@example.com>\r\n"
			+ "To: <sip:standup@example.com>\r\nDateTime: 2026-09-26T16:00:00Z\r\n\r\n"
			+ "Content-Type: text/plain;charset=utf-8\r\n\r\nhola, ¿qué tal?";

	@Test
	@DisplayName("CPIM is read to the text of its plain-text part")
	void readCpim() {
		assertEquals("hola, ¿qué tal?", MessageFormats.read("message/cpim", CPIM_BODY));
		assertEquals("line one\nline two", MessageFormats.read("message/cpim",
				"From: <sip:a@b>\n\nContent-Type: text/plain\n\nline one\nline two"));
	}

	@Test
	@DisplayName("a CPIM part that is not plain text, or is not CPIM at all, has no text")
	void unreadableCpim() {
		assertNull(MessageFormats.read("message/cpim", "From: <sip:a@b>\r\n\r\nContent-Type: image/png\r\n\r\nxx"));
		assertNull(MessageFormats.read("message/cpim", "just some words"));
		assertNull(MessageFormats.read("application/vnd.example", "{}"));
	}

	@Test
	@DisplayName("plain text, with or without a charset, and a missing type, read as themselves")
	void readPlain() {
		assertEquals("hi", MessageFormats.read("text/plain;charset=utf-8", "hi"));
		assertEquals("hi", MessageFormats.read(null, "hi"));
	}

	@Test
	@DisplayName("CPIM written by the room names the asserted sender, not the one the page wrote")
	void writeCpimVouchesForTheSender() {
		StoredMessage m = message(null);
		String body = MessageFormats.of("message/cpim").write(m, "bob@example.com");
		assertTrue(body.startsWith("From: \"Alice\" <sip:alice@example.com>\r\n"), body);
		assertTrue(body.contains("To: <sip:standup@example.com>\r\n"), body);
		assertTrue(body.contains("DateTime: 2026-09-26T16:00:00Z\r\n"), body);
		assertEquals("hola, ¿qué tal?", MessageFormats.read("message/cpim", body), "what the room writes, it reads back");
	}

	@Test
	@DisplayName("a private message's CPIM is addressed to its recipient")
	void writeCpimPrivate() {
		String body = MessageFormats.of("message/cpim").write(message("bob@example.com"), "bob@example.com");
		assertTrue(body.contains("To: <sip:bob@example.com>\r\n"), body);
	}

	@Test
	@DisplayName("a member who has not said, or takes the posted format, gets it as posted")
	void passThrough() {
		assertEquals("message/cpim", MessageFormats.choose(null, "message/cpim", true));
		assertEquals("message/cpim", MessageFormats.choose(Collections.emptyList(), "message/cpim", true));
		assertEquals("message/cpim;x=1", MessageFormats.choose(Arrays.asList("text/plain", "message/cpim"), "message/cpim;x=1", true));
		assertEquals("application/vnd.example", MessageFormats.choose(Arrays.asList("*/*"), "application/vnd.example", false));
	}

	@Test
	@DisplayName("a member who does not take the posted format gets the first one it takes that the room writes")
	void transcode() {
		assertEquals("text/plain", MessageFormats.choose(Arrays.asList("text/plain"), "message/cpim", true));
		assertEquals("message/cpim", MessageFormats.choose(Arrays.asList("message/cpim"), "text/plain", true));
		assertEquals("message/cpim", MessageFormats.choose(Arrays.asList("image/png", "message/cpim", "text/plain"), "text/html", true));
		assertEquals("text/plain", MessageFormats.choose(Arrays.asList("text/*"), "message/cpim", true));
	}

	@Test
	@DisplayName("nothing is sent a member who takes nothing the room can write, or when it could not read the text")
	void nothingToSend() {
		assertNull(MessageFormats.choose(Arrays.asList("image/png"), "text/plain", true));
		assertNull(MessageFormats.choose(Arrays.asList("text/plain"), "application/vnd.example", false));
	}

	@Test
	@DisplayName("Accept values are split, stripped of parameters, and kept in order")
	void parseAccept() {
		List<String> types = MessageFormats.parseAccept(Arrays.asList("message/cpim;q=0.9, text/plain", "TEXT/PLAIN", "junk").iterator());
		assertEquals(Arrays.asList("message/cpim", "text/plain"), types);
		assertTrue(MessageFormats.parseAccept(null).isEmpty());
	}

	@Test
	@DisplayName("wildcards accept what they cover and nothing else")
	void wildcards() {
		assertTrue(MessageFormats.accepts(Arrays.asList("text/*"), "text/plain"));
		assertFalse(MessageFormats.accepts(Arrays.asList("text/*"), "message/cpim"));
		assertTrue(MessageFormats.accepts(Arrays.asList("*/*"), "message/cpim"));
	}

	@Test
	@DisplayName("an archive reader, with no recipient, gets CPIM addressed to the room in the sender's domain")
	void writeCpimForAnArchiveReader() {
		String body = MessageFormats.of("message/cpim").write(message(null), null);
		assertTrue(body.contains("To: <sip:standup@example.com>\r\n"), body);
		assertEquals("hola, ¿qué tal?", MessageFormats.read("message/cpim", body));
	}

	private static StoredMessage message(String to) {
		StoredMessage m = new StoredMessage(3, "standup", "alice@example.com", "Alice", to,
				java.time.Instant.parse("2026-09-26T16:00:00Z").toEpochMilli(), "message/cpim", CPIM_BODY);
		m.setText("hola, ¿qué tal?");
		return m;
	}
}
