package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.vorpal.blade.framework.v3.events.CloudEvent;

public class FarSideEventTest {

	private static byte[] event(String type) {
		return ("{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"/meetings\",\"type\":\"" + type
				+ "\",\"subject\":\"room1\",\"data\":{\"speaker\":\"Alice\",\"text\":\"hello\"}}")
				.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void aMeetingEventIsRelayedUnderTheBrowsersCall() {
		CloudEvent e = WebrtcCallflow.farSideEvent("blade-event", "application/cloudevents+json", event("meeting.caption"), "call-7");
		assertNotNull(e);
		assertEquals("meeting.caption", e.getType());
		assertEquals("call-7", e.getSubject());
		assertEquals("hello", e.getData().path("text").asText());
	}

	@Test
	public void aProtocolEventFromTheFarSideIsRefused() {
		assertNull("a far side must not hang up the browser's call",
				WebrtcCallflow.farSideEvent("blade-event", "application/cloudevents+json", event("call.ended"), "call-7"));
	}

	@Test
	public void anythingThatIsNotAnEventIsRefused() {
		assertNull(WebrtcCallflow.farSideEvent(null, "application/dtmf-relay",
				"Signal=5".getBytes(StandardCharsets.UTF_8), "c"));
		assertNull(WebrtcCallflow.farSideEvent("blade-event", "application/cloudevents+json",
				"not json".getBytes(StandardCharsets.UTF_8), "c"));
		assertNull(WebrtcCallflow.farSideEvent(null, null, event("meeting.caption"), "c"));
	}

	@Test
	public void anotherInfoPackageIsNotRelayed() {
		assertNull(WebrtcCallflow.farSideEvent("dtmf", "application/cloudevents+json", event("meeting.caption"), "c"));
	}

	@Test
	public void aLegacyInfoWithNoPackageIsTakenOnItsContentType() {
		assertNotNull(WebrtcCallflow.farSideEvent(null, "application/cloudevents+json", event("meeting.caption"), "c"));
	}
}
