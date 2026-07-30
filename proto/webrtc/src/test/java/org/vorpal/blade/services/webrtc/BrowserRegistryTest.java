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
/// edge cases are where a browser silently stops receiving calls. These cover the three that matter:
/// a page reload replacing a binding, a socket that dies between the liveness check and the write,
/// and the "not on this node" answer that a cluster has to be able to distinguish from "gone".
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
	public void reconnectingReplacesTheOldSocketAndClosesIt() {
		FakeSocket first = socket("s1");
		FakeSocket second = socket("s2");
		BrowserRegistry.register("alice@example.com", first.session);

		BrowserRegistry.register("alice@example.com", second.session);

		// A reloaded page must not leave a ghost binding that swallows incoming calls.
		assertEquals(1, first.closed.get());
		assertNull(BrowserRegistry.addressOf(first.session));
		assertTrue(BrowserRegistry.deliver("alice@example.com", event()));
		assertEquals("the live socket receives it", 1, second.sent.size());
		assertEquals("the replaced socket does not", 0, first.sent.size());
	}

	@Test
	public void unregisteringAStaleSocketLeavesTheCurrentBindingAlone() {
		FakeSocket first = socket("s1");
		FakeSocket second = socket("s2");
		BrowserRegistry.register("alice@example.com", first.session);
		BrowserRegistry.register("alice@example.com", second.session);

		// The old socket's close event arrives after the reconnect — a normal ordering.
		assertNull(BrowserRegistry.unregister(first.session));

		assertTrue("the reconnected browser is still reachable", BrowserRegistry.isLocal("alice@example.com"));
	}

	@Test
	public void deliveryToAnUnknownAddressIsFalseNotAnError() {
		// False means "not mine" — the caller has to route it elsewhere in the cluster rather than
		// conclude the browser is gone.
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

	// ---- fakes ------------------------------------------------------------------------------

	private static CloudEvent event() {
		return SignalProtocol.reason(SignalProtocol.CALL_ENDED, "call-1", "test");
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
						throw new UnsupportedOperationException(m.getName());
					});
		}
	}
}
