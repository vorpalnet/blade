package org.vorpal.blade.framework.v3.events;

import java.util.ArrayList;
import java.util.List;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.vorpal.blade.framework.v3.metrics.Counter;

/// Consumes [CloudEvent]s from an event-bus destination — the counterpart to
/// [EventPublisher], and the reason a consumer can be reconfigured without
/// being redeployed.
///
/// ## Why this exists rather than an MDB
///
/// Consumers were `@MessageDriven` classes whose activation config —
/// destination, durability, subscription name, and above all the message
/// selector — are `@ActivationConfigProperty` values, which are Java
/// compile-time constants. A catalog edit could not reach a running consumer:
/// changing which event types an app wanted meant regenerating its source,
/// rebuilding its WAR and redeploying it.
///
/// That single constraint is what shaped the whole consumer side. It is why
/// analytics subscribed with **no** selector and filtered in application code:
/// a sink that must not miss a newly-declared type had no way to widen its
/// selector at runtime, so it took everything and paid for it in broker
/// storage and wasted deliveries. With the subscription owned here, the
/// selector is re-derived from the catalog on reload and the sink can filter at
/// the broker like every other consumer.
///
/// ## What it costs
///
/// The container's MDB pool is given up along with the annotation, and this
/// owns its own consumer thread. That is a straight improvement for a sink —
/// one thread committing a batch beats N threads committing a row each — but
/// it is a real constraint to know about: **a durable topic subscription
/// permits one active consumer**, so this runs a single consumer thread per
/// subscription. A consumer that needs more parallelism than one thread
/// wants a queue rather than a topic, or JMS 2.0 shared durable consumers.
/// (I have not verified shared durable consumers against OCCAS, so the code
/// does not assume them.)
///
/// ## Delivery and failure
///
/// The session is **transacted**, which is the other reason for leaving the
/// MDB behind. A handler that throws rolls the batch back and the broker
/// redelivers it; a message that can never succeed is caught by the
/// destination's redelivery limit and moved to its error destination. Nothing
/// is silently discarded — the previous consumer logged a persistence failure
/// and acknowledged the message anyway, so a database hiccup that was not a
/// connection error destroyed events.
///
/// Redelivery makes at-least-once the contract, so a handler must tolerate
/// seeing an event twice. Rolling a whole batch back redelivers messages that
/// individually succeeded, which is safe for the same reason: the analytics
/// writer's keys are computed from event identity, so a re-applied event
/// collides with its own row.
public class EventSubscriber {

	/// What a subscriber does with the events it receives.
	///
	/// A batch arrives as one unit and is acknowledged as one unit: return
	/// normally and the whole batch is committed, throw and the whole batch is
	/// redelivered.
	public interface Handler {

		/// @param batch one or more events, in the order they were received
		/// @throws Exception to roll the batch back for redelivery
		void handle(List<CloudEvent> batch) throws Exception;
	}

	/// Events per transaction. A sink can afford latency and cannot afford a
	/// round trip per row; an actor usually wants 1.
	public static final int DEFAULT_BATCH_SIZE = 64;

	/// How long a partial batch waits for company before being committed
	/// anyway. This is the latency an event can sit for, so it is short.
	public static final long DEFAULT_BATCH_MILLIS = 250L;

	private final String connectionFactoryJndi;
	private final String destinationJndi;
	private final String subscriptionName;
	private final String selector;
	private final boolean durable;
	private final int batchSize;
	private final long batchMillis;
	private final Handler handler;

	private Connection connection;
	private Session session;
	private MessageConsumer consumer;
	private Thread pump;
	private volatile boolean closed;
	private volatile boolean paused;

	/// Optional meters — see [#meter]. Null when nobody is counting.
	private volatile Counter.Series received;
	private volatile Counter.Series handled;
	private volatile Counter.Series failed;

	/// @param connectionFactoryJndi the connection factory JNDI name
	/// @param destinationJndi       the topic or queue JNDI name
	/// @param subscriptionName      this SUBSCRIBER's name — never an event
	///                              type's; used as both the durable
	///                              subscription name and the JMS client id
	/// @param selector              a JMS message selector, or null for all
	/// @param durable               whether the subscription survives a restart
	/// @param handler               what to do with each batch
	public EventSubscriber(String connectionFactoryJndi, String destinationJndi, String subscriptionName,
			String selector, boolean durable, Handler handler) {
		this(connectionFactoryJndi, destinationJndi, subscriptionName, selector, durable, handler,
				DEFAULT_BATCH_SIZE, DEFAULT_BATCH_MILLIS);
	}

	/// @param batchSize   events per transaction; 1 commits each event alone
	/// @param batchMillis how long a partial batch waits before committing
	public EventSubscriber(String connectionFactoryJndi, String destinationJndi, String subscriptionName,
			String selector, boolean durable, Handler handler, int batchSize, long batchMillis) {
		this.connectionFactoryJndi = connectionFactoryJndi;
		this.destinationJndi = destinationJndi;
		this.subscriptionName = subscriptionName;
		this.selector = (selector == null || selector.isEmpty()) ? null : selector;
		this.durable = durable;
		this.handler = handler;
		this.batchSize = Math.max(1, batchSize);
		this.batchMillis = Math.max(1L, batchMillis);
	}

	/// The subscription's name — the key the bus registers it under, and the
	/// identity the broker knows it by.
	public String getSubscriptionName() {
		return subscriptionName;
	}

	public String getDestinationJndi() {
		return destinationJndi;
	}

	/// The selector currently in force, or null when this subscriber takes
	/// everything. The bus compares this against the catalog on reload to
	/// decide whether a subscription has to be rebuilt.
	public String getSelector() {
		return selector;
	}

	public boolean isDurable() {
		return durable;
	}

	/// Stop taking messages off the destination without giving up the
	/// subscription.
	///
	/// **This is what "hold events until the database is back" actually
	/// means.** A durable subscription that is not being consumed accumulates
	/// on the broker's file store, which is exactly where a backlog should sit
	/// during an outage — not in this JVM's heap, and not discarded. The
	/// alternative, letting every batch fail and be redelivered, burns through
	/// the destination's redelivery limit and sends perfectly good events to
	/// the error destination for the crime of arriving during a database
	/// restart.
	///
	/// Whoever pauses is responsible for resuming; see the health check in the
	/// analytics sink for the shape.
	public void pause() {
		paused = true;
	}

	/// Resume consuming after [#pause].
	public void resume() {
		paused = false;
	}

	public boolean isPaused() {
		return paused;
	}

	/// Wire the meters. Optional; an unmetered subscriber behaves identically.
	///
	/// @param received incremented per message taken off the destination
	/// @param handled  incremented per message in a committed batch
	/// @param failed   incremented per message in a rolled-back batch
	public void meter(Counter.Series received, Counter.Series handled, Counter.Series failed) {
		this.received = received;
		this.handled = handled;
		this.failed = failed;
	}

	/// Open the connection, establish the subscription, and start consuming.
	///
	/// @throws NamingException if a JNDI lookup fails
	/// @throws JMSException    if the subscription cannot be established
	public void init() throws NamingException, JMSException {
		InitialContext ctx = new InitialContext();
		ConnectionFactory factory = (ConnectionFactory) ctx.lookup(connectionFactoryJndi);
		Destination destination = (Destination) ctx.lookup(destinationJndi);

		connection = factory.createConnection();
		if (durable) {
			// The client id is the subscription's identity to the broker. Two
			// apps that shared one would not be two subscriptions competing —
			// they would be one subscription named twice, each app receiving
			// part of the stream. See EventSubscription.
			connection.setClientID(subscriptionName);
		}
		session = connection.createSession(true, Session.SESSION_TRANSACTED);
		if (durable && destination instanceof Topic) {
			consumer = session.createDurableSubscriber((Topic) destination, subscriptionName, selector, false);
		} else {
			consumer = session.createConsumer(destination, selector);
		}
		connection.start();

		pump = new Thread(this::pump, "blade-events-" + subscriptionName);
		pump.setDaemon(true);
		pump.start();
	}

	/// Receive, batch, hand off, commit. One thread, for the durable-consumer
	/// reason in the class comment.
	private void pump() {
		List<CloudEvent> batch = new ArrayList<>();
		long deadline = 0L;

		while (!closed) {
			try {
				if (paused) {
					// Commit anything already in hand before going quiet, so a
					// pause does not hold a batch open across an outage.
					if (!batch.isEmpty()) {
						flush(batch);
					}
					Thread.sleep(batchMillis);
					continue;
				}

				long wait = batch.isEmpty() ? batchMillis : Math.max(1L, deadline - System.currentTimeMillis());
				Message message = consumer.receive(wait);

				if (message != null) {
					if (batch.isEmpty()) {
						deadline = System.currentTimeMillis() + batchMillis;
					}
					count(received);
					CloudEvent event = parse(message);
					if (event != null) {
						batch.add(event);
					}
				}

				boolean full = batch.size() >= batchSize;
				boolean expired = !batch.isEmpty() && System.currentTimeMillis() >= deadline;
				if (full || expired || (message == null && !batch.isEmpty())) {
					flush(batch);
				}
			} catch (JMSException e) {
				// The connection is going away — either a shutdown, which is
				// expected, or a broker failure, which the container's own
				// connection handling deals with. Either way this thread is
				// done; anything uncommitted is redelivered.
				if (!closed) {
					rollback();
				}
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				rollback();
				batch.clear();
			}
		}
		if (!batch.isEmpty()) {
			flush(batch);
		}
	}

	/// Hand a batch to the handler and commit it, or roll it back.
	///
	/// The batch is cleared either way: a rolled-back batch is redelivered by
	/// the broker, so keeping it here would process every message twice.
	private void flush(List<CloudEvent> batch) {
		int size = batch.size();
		try {
			handler.handle(batch);
			session.commit();
			count(handled, size);
		} catch (Exception e) {
			count(failed, size);
			rollback();
		} finally {
			batch.clear();
		}
	}

	private void rollback() {
		try {
			if (session != null) {
				session.rollback();
			}
		} catch (JMSException e) {
			// Nothing further to do: the broker redelivers whatever this
			// session did not commit.
		}
	}

	/// A JMS message as a CloudEvent, or null when it is not one.
	private CloudEvent parse(Message message) {
		if (!(message instanceof TextMessage)) {
			return null;
		}
		try {
			return CloudEvent.fromJson(((TextMessage) message).getText());
		} catch (Exception e) {
			// Malformed JSON on the bus. Returning null drops it from the
			// batch rather than rolling back, because redelivering a message
			// that cannot be parsed would loop until the redelivery limit
			// catches it — the same outcome, several attempts later.
			return null;
		}
	}

	private static void count(Counter.Series series) {
		count(series, 1);
	}

	private static void count(Counter.Series series, int times) {
		if (series != null) {
			for (int i = 0; i < times; i++) {
				series.increment();
			}
		}
	}

	/// Stop consuming and close everything.
	///
	/// A durable subscription is left registered with the broker on purpose:
	/// that is what makes it durable, and it is how events accumulate for a
	/// consumer that is being redeployed rather than retired. Use
	/// [#unsubscribe] to actually remove it.
	public void close() {
		closed = true;
		try {
			if (consumer != null) {
				consumer.close();
			}
		} catch (JMSException e) {
			// Shutting down regardless.
		}
		if (pump != null) {
			try {
				pump.join(batchMillis * 4);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		try {
			if (connection != null) {
				connection.close();
			}
		} catch (JMSException e) {
			// Shutting down regardless.
		}
	}

	/// Close, then remove the durable subscription from the broker.
	///
	/// Only for a subscription that is genuinely going away. Calling this on a
	/// redeploy would discard everything the broker held while the app was
	/// down, which is the one thing durability is for.
	public void unsubscribe() {
		closed = true;
		try {
			if (consumer != null) {
				consumer.close();
			}
			if (durable && session != null) {
				session.unsubscribe(subscriptionName);
			}
		} catch (JMSException e) {
			// Best effort: the subscription may already be gone.
		} finally {
			close();
		}
	}
}
