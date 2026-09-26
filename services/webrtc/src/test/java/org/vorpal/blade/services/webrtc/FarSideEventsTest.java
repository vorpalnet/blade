package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// An application's event, published on the bus to a call's Vorpal-ID, reaches the socket of the
/// browser holding that call, filed under the browser's own call id.
public class FarSideEventsTest {

	private static final String AOR = "pat@vorpal.net";
	private static final String VORPAL_ID = "CEBA68AF";
	private static final String CALL_ID = "app-session-1";

	private final List<String> sent = new ArrayList<>();
	private Session socket;

	@Before
	public void aBrowserHoldsACall() {
		RemoteEndpoint.Basic remote = (RemoteEndpoint.Basic) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { RemoteEndpoint.Basic.class }, (proxy, method, args) -> {
					if ("sendText".equals(method.getName())) {
						sent.add((String) args[0]);
					}
					return null;
				});
		socket = (Session) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Session.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
					case "getId":
						return "socket-1";
					case "isOpen":
						return true;
					case "getBasicRemote":
						return remote;
					case "hashCode":
						return 1;
					case "equals":
						return proxy == args[0];
					default:
						return null;
					}
				});
		BrowserRegistry.register(AOR, socket);
		BrowserCalls.remember(VORPAL_ID, AOR, CALL_ID);
	}

	@After
	public void theCallEnds() {
		BrowserCalls.forget(CALL_ID);
		BrowserRegistry.unregister(socket);
	}

	private static CloudEvent event(String type, String subject) {
		ObjectNode data = new ObjectMapper().createObjectNode();
		data.put("speaker", "Alice");
		data.put("text", "hello");
		return CloudEvent.create(type, "/meetings", subject, data);
	}

	@Test
	public void aMeetingEventReachesTheBrowserUnderItsOwnCallId() throws Exception {
		new FarSideEvents().handle(Collections.singletonList(event("meeting.caption", VORPAL_ID)));

		assertEquals(1, sent.size());
		CloudEvent delivered = CloudEvent.fromJson(sent.get(0));
		assertEquals("meeting.caption", delivered.getType());
		assertEquals(CALL_ID, delivered.getSubject());
		assertEquals("hello", delivered.getData().path("text").asText());
	}

	@Test
	public void theFrameworksTimestampedSubjectFindsTheCallToo() throws Exception {
		new FarSideEvents().handle(Collections.singletonList(event("meeting.roster", VORPAL_ID + ".18F2A3B4C5D")));
		assertEquals(1, sent.size());
	}

	@Test
	public void aCallThisNodeDoesNotHoldIsIgnored() throws Exception {
		new FarSideEvents().handle(Collections.singletonList(event("meeting.caption", "0000BEEF")));
		assertTrue("another node's browser, or a call that has ended", sent.isEmpty());
	}

	@Test
	public void aProtocolEventFromTheFarSideIsRefused() throws Exception {
		new FarSideEvents().handle(Collections.singletonList(event("call.ended", VORPAL_ID)));
		assertTrue("a far side must not hang up the browser's call", sent.isEmpty());
		assertNull(FarSideEvents.forBrowser(event("call.update", VORPAL_ID), CALL_ID));
	}

	@Test
	public void anEndedCallTakesNoMoreEvents() throws Exception {
		BrowserCalls.forget(CALL_ID);
		new FarSideEvents().handle(Collections.singletonList(event("meeting.caption", VORPAL_ID)));
		assertTrue(sent.isEmpty());
	}

	@Test
	public void theSubjectsVorpalIdIsThePartBeforeTheTimestamp() {
		assertEquals("CEBA68AF", FarSideEvents.vorpalIdOf("CEBA68AF.18F2A3B4C5D"));
		assertEquals("CEBA68AF", FarSideEvents.vorpalIdOf("CEBA68AF"));
		assertNull(FarSideEvents.vorpalIdOf(null));
	}
}
