package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.junit.jupiter.api.Test;

/// The publisher outlives the server that hosts its destination: a restart
/// kills the shared connection, and the publisher must open another rather
/// than fail every publish until a redeploy.
class EventPublisherTest {

	/// A broker stand-in: counts connections opened and closed and messages
	/// sent, and fails every send on a connection opened before it went down.
	static final class Broker {
		final AtomicInteger opened = new AtomicInteger();
		final AtomicInteger closed = new AtomicInteger();
		final AtomicInteger sent = new AtomicInteger();
		/// Bumped by a restart: a connection from an older generation is dead.
		final AtomicInteger generation = new AtomicInteger();

		ConnectionFactory factory() {
			return proxy(ConnectionFactory.class, (p, m, a) -> {
				if (m.getName().equals("createConnection")) {
					opened.incrementAndGet();
					return connection(generation.get());
				}
				throw new UnsupportedOperationException(m.getName());
			});
		}

		private Connection connection(int born) {
			AtomicBoolean shut = new AtomicBoolean();
			return proxy(Connection.class, (p, m, a) -> {
				switch (m.getName()) {
				case "start":
					return null;
				case "close":
					if (!shut.getAndSet(true)) {
						closed.incrementAndGet();
					}
					return null;
				case "createSession":
					return session(born);
				default:
					throw new UnsupportedOperationException(m.getName());
				}
			});
		}

		private Session session(int born) {
			return proxy(Session.class, (p, m, a) -> {
				switch (m.getName()) {
				case "createTextMessage":
					return proxy(TextMessage.class, (q, n, b) -> null);
				case "createProducer":
					return producer(born);
				case "close":
					return null;
				default:
					throw new UnsupportedOperationException(m.getName());
				}
			});
		}

		private MessageProducer producer(int born) {
			return proxy(MessageProducer.class, (p, m, a) -> {
				switch (m.getName()) {
				case "send":
					if (born != generation.get()) {
						throw new JMSException("Network connection to server was lost.");
					}
					sent.incrementAndGet();
					return null;
				case "close":
					return null;
				default:
					throw new UnsupportedOperationException(m.getName());
				}
			});
		}
	}

	@SuppressWarnings("unchecked")
	static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
	}

	private static EventPublisher publisher(Broker broker) throws Exception {
		EventPublisher publisher = new EventPublisher("cf", "topic") {
			@Override
			void lookup() {
				factory = broker.factory();
				destination = proxy(Destination.class, (p, m, a) -> null);
			}
		};
		publisher.init();
		return publisher;
	}

	private static CloudEvent event() {
		return CloudEvent.create("org.vorpal.blade.server.sampled", "test", null, null);
	}

	@Test
	void aRestartOfTheBrokerCostsOnlyThePublishesWhileItIsDown() throws Exception {
		Broker broker = new Broker();
		EventPublisher publisher = publisher(broker);
		publisher.publish(event());
		assertEquals(1, broker.sent.get());

		broker.generation.incrementAndGet(); // the hosting server restarted
		assertThrows(JMSException.class, () -> publisher.publish(event()), "sent on the dead connection");
		assertEquals(1, broker.closed.get(), "the dead connection is let go");

		assertThrows(JMSException.class, () -> publisher.publish(event()),
				"within the reconnect interval the publish fails at once");
		assertEquals(1, broker.opened.get(), "and opens nothing");

		publisher.reconnectIntervalMs = 0;
		publisher.publish(event());
		publisher.publish(event());
		assertEquals(3, broker.sent.get(), "both publishes after the interval are sent");
		assertEquals(2, broker.opened.get(), "on one new connection");
	}

	@Test
	void aClosedPublisherDoesNotReconnect() throws Exception {
		Broker broker = new Broker();
		EventPublisher publisher = publisher(broker);
		publisher.reconnectIntervalMs = 0;
		publisher.close();
		JMSException e = assertThrows(JMSException.class, () -> publisher.publish(event()));
		assertTrue(e.getMessage().contains("closed"), e.getMessage());
		assertEquals(1, broker.opened.get());
	}
}
