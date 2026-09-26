package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

import org.junit.After;
import org.junit.Test;

import org.vorpal.blade.framework.v3.events.CloudEvent;

/// [BrowserRegistry] is the only piece of this application that holds non-replicable state, so its
/// edge cases are where a browser silently stops receiving calls: one account on several devices, a
/// socket that dies between the liveness check and the write, and the "not on this node" answer,
/// which means the binding is stale rather than that the address is unknown.
public class BrowserRegistryTest {

	private final List<FakeSocket> opened = new ArrayList<>();

	@After
	public void tearDown() {
		for (FakeSocket socket : opened) {
			BrowserRegistry.unregister(socket.session);
		}
	}

	@Test
	public void registeringMakesTheBrowserReachableOnThisNode() {
		FakeSocket socket = socket("s1");

		BrowserRegistry.register("alice@example.com", socket.session);

		assertTrue(BrowserRegistry.isLocal("alice@example.com"));
		assertEquals("alice@example.com", BrowserRegistry.addressOf(socket.session));
		assertTrue(BrowserRegistry.deliver("alice@example.com", event()));
		assertEquals(1, socket.sent.size());
	}

	@Test
	public void deliveryToAnUnknownAddressIsFalseNotAnError() {
		// False means "not mine", which is a stale binding rather than an error — the caller
		// answers 480 rather than treating it as a malformed address.
		assertFalse(BrowserRegistry.deliver("nobody@example.com", event()));
	}

	@Test
	public void aClosedSocketIsNotDeliverable() {
		FakeSocket socket = socket("s1");
		BrowserRegistry.register("alice@example.com", socket.session);
		socket.open = false;

		assertFalse(BrowserRegistry.isLocal("alice@example.com"));
		assertFalse(BrowserRegistry.deliver("alice@example.com", event()));
	}

	@Test
	public void aSocketThatFailsMidWriteIsDroppedFromTheRegistry() {
		FakeSocket socket = socket("s1");
		socket.failOnSend = true;
		BrowserRegistry.register("alice@example.com", socket.session);

		assertFalse(BrowserRegistry.deliver("alice@example.com", event()));

		// Otherwise every later call would keep writing into a dead socket instead of failing over.
		assertFalse(BrowserRegistry.isLocal("alice@example.com"));
		assertNull(BrowserRegistry.addressOf(socket.session));
	}

	@Test
	public void unregisterReturnsTheAddressItReleased() {
		FakeSocket socket = socket("s1");
		BrowserRegistry.register("bob@example.com", socket.session);

		assertEquals("bob@example.com", BrowserRegistry.unregister(socket.session));
		assertNull("a second close is a no-op", BrowserRegistry.unregister(socket.session));
	}

	@Test
	public void everyConnectedBrowserIsPinged() {
		FakeSocket alice = socket("s1");
		FakeSocket bob = socket("s2");
		BrowserRegistry.register("alice@example.com", alice.session);
		BrowserRegistry.register("bob@example.com", bob.session);

		// A proxy closes a socket that carries nothing for its idle timeout, ending the call.
		assertEquals(2, BrowserRegistry.pingAll());
		assertEquals(1, alice.pings.get());
		assertEquals(1, bob.pings.get());
	}

	@Test
	public void aSocketThePingCannotReachIsDropped() {
		FakeSocket socket = socket("s1");
		socket.failOnSend = true;
		BrowserRegistry.register("alice@example.com", socket.session);

		assertEquals(0, BrowserRegistry.pingAll());

		assertFalse(BrowserRegistry.isLocal("alice@example.com"));
		assertEquals(1, socket.closed.get());
	}

	@Test
	public void aSecondDeviceJoinsAlongsideTheFirst() {
		FakeSocket laptop = socket("s1");
		FakeSocket phone = socket("s2");
		BrowserRegistry.register("alice@example.com", laptop.session);

		BrowserRegistry.register("alice@example.com", phone.session);

		assertEquals("the first device stays connected", 0, laptop.closed.get());
		assertEquals("alice@example.com", BrowserRegistry.addressOf(laptop.session));
		assertEquals("alice@example.com", BrowserRegistry.addressOf(phone.session));
	}

	@Test
	public void anUnboundCallRingsEveryDevice() {
		FakeSocket laptop = socket("s1");
		FakeSocket phone = socket("s2");
		BrowserRegistry.register("alice@example.com", laptop.session);
		BrowserRegistry.register("alice@example.com", phone.session);

		assertTrue(BrowserRegistry.deliver("alice@example.com", event("call-1")));

		assertEquals(1, laptop.sent.size());
		assertEquals(1, phone.sent.size());
	}

	@Test
	public void aBoundCallReachesOnlyTheDeviceHoldingIt() {
		FakeSocket laptop = socket("s1");
		FakeSocket phone = socket("s2");
		BrowserRegistry.register("alice@example.com", laptop.session);
		BrowserRegistry.register("alice@example.com", phone.session);
		assertTrue(BrowserRegistry.bind("call-1", phone.session));

		try {
			assertTrue(BrowserRegistry.deliver("alice@example.com", event("call-1")));

			assertEquals("a page never sees another device's call", 0, laptop.sent.size());
			assertEquals(1, phone.sent.size());
		} finally {
			BrowserRegistry.forgetCall("call-1");
		}
	}

	@Test
	public void theFirstDeviceToAnswerTakesTheCall() {
		FakeSocket laptop = socket("s1");
		FakeSocket phone = socket("s2");
		BrowserRegistry.register("alice@example.com", laptop.session);
		BrowserRegistry.register("alice@example.com", phone.session);

		try {
			assertTrue(BrowserRegistry.bind("call-1", phone.session));
			assertFalse("the second answer is refused", BrowserRegistry.bind("call-1", laptop.session));
			assertTrue("the holder binding again is harmless", BrowserRegistry.bind("call-1", phone.session));

			BrowserRegistry.deliverToOthers("alice@example.com", phone.session, event("call-1"));
			assertEquals("the other device stops ringing", 1, laptop.sent.size());
			assertEquals(0, phone.sent.size());
		} finally {
			BrowserRegistry.forgetCall("call-1");
		}
	}

	@Test
	public void theAddressIsReleasedOnlyWithItsLastDevice() {
		FakeSocket laptop = socket("s1");
		FakeSocket phone = socket("s2");
		BrowserRegistry.register("alice@example.com", laptop.session);
		BrowserRegistry.register("alice@example.com", phone.session);

		// Withdrawing the address while the phone is still connected would stop its incoming calls.
		assertNull(BrowserRegistry.unregister(laptop.session));
		assertTrue(BrowserRegistry.isLocal("alice@example.com"));
		assertEquals("alice@example.com", BrowserRegistry.unregister(phone.session));
		assertFalse(BrowserRegistry.isLocal("alice@example.com"));
	}

	@Test
	public void aClosingDeviceTakesOnlyItsOwnCalls() {
		FakeSocket laptop = socket("s1");
		FakeSocket phone = socket("s2");
		BrowserRegistry.register("alice@example.com", laptop.session);
		BrowserRegistry.register("alice@example.com", phone.session);
		BrowserRegistry.bind("call-1", laptop.session);
		BrowserRegistry.bind("call-2", phone.session);

		try {
			assertEquals(java.util.Collections.singletonList("call-1"), BrowserRegistry.callsOf(laptop.session));
			BrowserRegistry.unregister(laptop.session);

			assertNull("the closed device's call is unbound", BrowserRegistry.holderOf("call-1"));
			assertEquals(phone.session, BrowserRegistry.holderOf("call-2"));
		} finally {
			BrowserRegistry.forgetCall("call-1");
			BrowserRegistry.forgetCall("call-2");
		}
	}

	// ---- fakes ------------------------------------------------------------------------------

	private static CloudEvent event() {
		return event("call-0");
	}

	private static CloudEvent event(String callId) {
		return SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId, "test");
	}

	private FakeSocket socket(String id) {
		FakeSocket socket = new FakeSocket(id);
		opened.add(socket);
		return socket;
	}

	/// A `javax.websocket.Session` stand-in. Only four members are ever touched, so a dynamic proxy
	/// is a lot less code than implementing the whole interface.
	private static final class FakeSocket implements InvocationHandler {
		final String id;
		final List<String> sent = new ArrayList<>();
		final AtomicInteger closed = new AtomicInteger();
		final Session session;
		final AtomicInteger pings = new AtomicInteger();
		boolean open = true;
		boolean failOnSend;

		FakeSocket(String id) {
			this.id = id;
			this.session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
					new Class<?>[] { Session.class }, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			switch (method.getName()) {
			case "getId":
				return id;
			case "isOpen":
				return open;
			case "close":
				closed.incrementAndGet();
				open = false;
				return null;
			case "getBasicRemote":
				return basicRemote();
			case "equals":
				return proxy == args[0];
			case "hashCode":
				return System.identityHashCode(proxy);
			case "toString":
				return "FakeSocket[" + id + "]";
			default:
				throw new UnsupportedOperationException(method.getName());
			}
		}

		private RemoteEndpoint.Basic basicRemote() {
			return (RemoteEndpoint.Basic) Proxy.newProxyInstance(RemoteEndpoint.Basic.class.getClassLoader(),
					new Class<?>[] { RemoteEndpoint.Basic.class }, (p, m, a) -> {
						if ("sendText".equals(m.getName())) {
							if (failOnSend) {
								throw new IOException("socket gone");
							}
							sent.add((String) a[0]);
							return null;
						}
						if ("sendPing".equals(m.getName())) {
							if (failOnSend) {
								throw new IOException("socket gone");
							}
							pings.incrementAndGet();
							return null;
						}
						throw new UnsupportedOperationException(m.getName());
					});
		}
	}
}
