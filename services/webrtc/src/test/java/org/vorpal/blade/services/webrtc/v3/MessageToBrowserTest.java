package org.vorpal.blade.services.webrtc.v3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import org.junit.Test;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.messaging.RoomHeaders;
import org.vorpal.blade.services.webrtc.SignalProtocol;

import com.fasterxml.jackson.databind.JsonNode;

/// A room's MESSAGE becomes the page's `message.received`: the envelope from the room's headers, the
/// body untouched.
public class MessageToBrowserTest {

	private static final String CPIM = "From: <sip:alice@example.com>\r\nTo: <sip:standup@example.com>\r\n\r\n"
			+ "Content-Type: text/plain\r\n\r\nhola, ¿qué tal?";

	@Test
	public void theEnvelopeComesFromTheRoomsHeaders() throws Exception {
		Map<String, String> headers = new HashMap<>();
		headers.put(RoomHeaders.ROOM, "standup");
		headers.put(RoomHeaders.FROM, "alice@example.com");
		headers.put(RoomHeaders.NAME, "Alice");
		headers.put(RoomHeaders.SEQ, "42");
		headers.put(RoomHeaders.TIME, "1790000000000");

		CloudEvent event = MessageToBrowser.received(message(headers, "message/cpim", CPIM));
		JsonNode data = event.getData();

		assertEquals(SignalProtocol.MESSAGE_RECEIVED, event.getType());
		assertEquals("standup", data.path("room").asText());
		assertEquals(42, data.path("seq").asInt());
		assertEquals("alice@example.com", data.path("from").asText());
		assertEquals("Alice", data.path("displayName").asText());
		assertEquals(1790000000000L, data.path("atMs").asLong());
		assertFalse("a message to the room names no one", data.has("to"));
		assertEquals("message/cpim", data.path("contentType").asText());
		assertEquals("the body is the page's, byte for byte", CPIM, data.path("body").asText());
	}

	@Test
	public void aPrivateMessageSaysWhoItWasFor() throws Exception {
		Map<String, String> headers = new HashMap<>();
		headers.put(RoomHeaders.ROOM, "standup");
		headers.put(RoomHeaders.FROM, "alice@example.com");
		headers.put(RoomHeaders.SEQ, "7");
		headers.put(RoomHeaders.TO, "bob@example.com");

		JsonNode data = MessageToBrowser.received(message(headers, "text/plain", "psst")).getData();
		assertEquals("bob@example.com", data.path("to").asText());
	}

	@Test
	public void aNumberHeaderThatIsNotANumberReadsAsZero() {
		assertEquals(0, MessageFromBrowser.number(null));
		assertEquals(0, MessageFromBrowser.number("seven"));
		assertEquals(7, MessageFromBrowser.number(" 7 "));
	}

	private static SipServletRequest message(Map<String, String> headers, String contentType, String body) {
		return (SipServletRequest) Proxy.newProxyInstance(MessageToBrowserTest.class.getClassLoader(),
				new Class<?>[] { SipServletRequest.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "getHeader":
						return headers.get(args[0]);
					case "getContentType":
						return contentType;
					case "getRawContent":
						return body.getBytes(StandardCharsets.UTF_8);
					default:
						throw new UnsupportedOperationException(method.getName());
					}
				});
	}
}
