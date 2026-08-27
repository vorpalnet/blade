package org.vorpal.blade.framework.v3.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
/// ## One consumer per member, not one per subscription
///
/// The bus destination is a *partitioned distributed topic*, and a durable
/// subscription cannot be created on its logical name — the broker refuses:
///
/// ```
/// [JMSClientExceptions:055030] This topic does not support durable subscriptions.
/// ```
///
/// An MDB does not meet this because the container subscribes to every physical
/// member for it. This does the same explicitly: [DistributedMembers] reports
/// the members, and each gets its own connection, session, durable subscriber
/// and consumer thread, under a subscription name derived from this
/// subscription's name and the member's. Members arriving and leaving — a
/// server restarting, a migration — add and remove consumers while the rest
/// keep running.
///
/// Where members cannot be discovered (a plain topic, a queue, or a container
/// that does not expose the extensions) it falls back to a single consumer on
/// the destination as looked up. That fallback is also the non-durable path,
/// which needs no member handling at all.
///
/// ## What it costs
///
/// The container's MDB pool is given up along with the annotation. A durable
/// topic subscription permits one active consumer, so there is one thread per
/// member rather than a pool — a straight improvement for a sink, where one
/// thread committing a batch beats N threads committing a row each, but a real
/// constraint for a consumer that needs parallelism within a member. That one
/// wants a queue, or JMS 2.0 shared durable consumers, which are not assumed
/// here because I have not verified them against OCCAS.
///
/// ## Delivery and failure
///
/// Each member's session is **transacted**, which is the other reason for
/// leaving the MDB behind. A handler that throws rolls its batch back and the
/// broker redelivers it; a message that can never succeed is caught by the
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
	///
	/// **Called from one thread per member**, so an implementation that keeps
	/// mutable state needs to say how it is shared.
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

	/// The key the single non-distributed consumer is held under.
	private static final String SOLE = "";

	private final String connectionFactoryJndi;
	private final String destinationJndi;
	private final String subscriptionName;
	private final String selector;
	private final boolean durable;
	private final int batchSize;
	private final long batchMillis;
	private final Handler handler;

	private final ConcurrentMap<String, Leg> legs = new ConcurrentHashMap<>();
	private ConnectionFactory factory;
	private Object membership;
	private volatile boolean closed;
	private volatile boolean paused;

	/// Optional meters — see [#meter]. Null when nobody is counting.
	private volatile Counter.Series received;
	private volatile Counter.Series handled;
	private volatile Counter.Series failed;

	/// @param connectionFactoryJndi the connection factory JNDI name
	/// @param destinationJndi       the topic or queue JNDI name
	/// @param subscriptionName      this SUBSCRIBER's name — never an event
	///                              type's; the durable subscription name and
	///                              JMS client id are derived from it
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
	/// stem of the name the broker knows each member's subscription by.
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

	/// How many members are being consumed right now. One for an ordinary
	/// destination; one per live member of a distributed one.
	public int getConsumerCount() {
		return legs.size();
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

	/// Establish the subscription and start consuming.
	///
	/// @throws NamingException if a JNDI lookup fails
	/// @throws JMSException    if the subscription cannot be established
	public void init() throws NamingException, JMSException {
		InitialContext ctx = new InitialContext();
		factory = (ConnectionFactory) ctx.lookup(connectionFactoryJndi);
		Destination destination = (Destination) ctx.lookup(destinationJndi);

		if (durable) {
			membership = DistributedMembers.register(destinationJndi, new DistributedMembers.Listener() {
				@Override
				public void onAvailable(String memberName, Destination member) {
					addMember(memberName, member);
				}

				@Override
				public void onUnavailable(String memberName) {
					removeMember(memberName);
				}
			});
			if (membership != null) {
				// Members arrive through the listener, including the ones that
				// already exist. There is nothing to open here.
				return;
			}
		}

		// Not distributed, not durable, or no member discovery available: one
		// consumer on the destination as looked up.
		boolean started = false;
		try {
			legs.put(SOLE, open(SOLE, destination, subscriptionName));
			started = true;
		} finally {
			if (!started) {
				closeAll();
			}
		}
	}

	/// Start consuming one member. Failure is contained to that member: the
	/// others keep running and this one is retried when the container next
	/// reports it available.
	private void addMember(String memberName, Destination member) {
		if (closed || legs.containsKey(memberName)) {
			return;
		}
		try {
			// The subscription name and client id must be unique per member —
			// they ARE the subscription's identity to the broker, and reusing
			// one across members would make several consumers fight over a
			// single stream instead of each covering its own partition.
			legs.put(memberName, open(memberName, member, subscriptionName + "@" + memberName));
		} catch (Exception e) {
			legs.remove(memberName);
		}
	}

	private void removeMember(String memberName) {
		Leg leg = legs.remove(memberName);
		if (leg != null) {
			leg.close();
		}
	}

	/// One member's connection, session, consumer and thread.
	private Leg open(String memberName, Destination destination, String name)
			throws JMSException {
		Leg leg = new Leg(memberName, name);
		boolean started = false;
		try {
			leg.connection = factory.createConnection();
			if (durable) {
				leg.connection.setClientID(name);
			}
			leg.session = leg.connection.createSession(true, Session.SESSION_TRANSACTED);
			if (durable && destination instanceof Topic) {
				leg.consumer = leg.session.createDurableSubscriber((Topic) destination, name, selector, false);
			} else {
				leg.consumer = leg.session.createConsumer(destination, selector);
			}
			leg.connection.start();

			leg.pump = new Thread(leg::pump, "blade-events-" + name);
			leg.pump.setDaemon(true);
			leg.pump.start();
			started = true;
			return leg;
		} finally {
			if (!started) {
				// A half-built consumer must not keep its connection. The
				// client id is bound the moment setClientID succeeds, so
				// leaking one makes EVERY later attempt fail with "Client id is
				// in use" — turning one recoverable error into a permanent one
				// that points somewhere else entirely.
				leg.closeQuietly();
			}
		}
	}

	private final class Leg {

		private final String memberName;
		private final String name;
		private Connection connection;
		private Session session;
		private MessageConsumer consumer;
		private Thread pump;
		private volatile boolean legClosed;

		/// How many events this member batches right now.
		///
		/// **Drops to one after any failed flush, and only goes back up after a
		/// clean one.** A transacted JMS session cannot acknowledge part of a
		/// batch, so a batch containing one message that always fails takes its
		/// neighbours down with it: all of them roll back, all of them are
		/// redelivered together, all of them accumulate redelivery counts, and
		/// all of them end up on the error destination — sixty-three good
		/// events destroyed by one bad one, in exactly the scenario the error
		/// destination exists to contain.
		///
		/// The batch in hand cannot be salvaged, so the NEXT pass is narrowed
		/// instead. Redelivered one at a time, the poison message fails alone
		/// and is parked alone while its neighbours commit.
		private int currentBatchSize = batchSize;

		private Leg(String memberName, String name) {
			this.memberName = memberName;
			this.name = name;
		}

		/// Receive, batch, hand off, commit.
		private void pump() {
			List<CloudEvent> batch = new ArrayList<>();
			long deadline = 0L;

			while (!closed && !legClosed) {
				try {
					if (paused) {
						// Commit anything already in hand before going quiet,
						// so a pause does not hold a batch open across an
						// outage.
						if (!batch.isEmpty()) {
							flush(batch);
						}
						Thread.sleep(batchMillis);
						continue;
					}

					long wait = batch.isEmpty() ? batchMillis
							: Math.max(1L, deadline - System.currentTimeMillis());
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

					boolean full = batch.size() >= currentBatchSize;
					boolean expired = !batch.isEmpty() && System.currentTimeMillis() >= deadline;
					if (full || expired || (message == null && !batch.isEmpty())) {
						flush(batch);
					}
				} catch (JMSException e) {
					// This member is going away — a shutdown, a migration, or a
					// broker failure. Anything uncommitted is redelivered, and
					// the member is re-opened if the container reports it
					// available again.
					if (!closed && !legClosed) {
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
		/// The batch is cleared either way: a rolled-back batch is redelivered
		/// by the broker, so keeping it here would process every message twice.
		private void flush(List<CloudEvent> batch) {
			int size = batch.size();
			try {
				handler.handle(batch);
				session.commit();
				count(handled, size);
				if (currentBatchSize != batchSize) {
					// A clean pass: whatever was poisoning this stream is past,
					// so stop paying one transaction per event.
					currentBatchSize = batchSize;
				}
			} catch (Exception e) {
				count(failed, size);
				rollback();
				if (size > 1) {
					currentBatchSize = 1;
				}
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

		private void close() {
			legClosed = true;
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
			closeConnection();
		}

		/// Release everything without reporting anything. Used on a failed
		/// open, where the caller already has the real exception.
		private void closeQuietly() {
			legClosed = true;
			try {
				if (consumer != null) {
					consumer.close();
				}
			} catch (JMSException e) {
				// Already failing.
			}
			closeConnection();
		}

		private void closeConnection() {
			try {
				if (session != null) {
					session.close();
				}
			} catch (JMSException e) {
				// Shutting down regardless.
			}
			try {
				if (connection != null) {
					connection.close();
				}
			} catch (JMSException e) {
				// Same.
			}
			session = null;
			connection = null;
			consumer = null;
		}

		/// Remove this member's durable subscription from the broker.
		private void unsubscribe() {
			try {
				if (consumer != null) {
					consumer.close();
					consumer = null;
				}
				if (durable && session != null) {
					session.unsubscribe(name);
				}
			} catch (JMSException e) {
				// Best effort: it may already be gone.
			}
		}

		@Override
		public String toString() {
			return name + (memberName.isEmpty() ? "" : " on " + memberName);
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
		DistributedMembers.unregister(membership);
		membership = null;
		closeAll();
	}

	private void closeAll() {
		for (String member : new ArrayList<>(legs.keySet())) {
			Leg leg = legs.remove(member);
			if (leg != null) {
				leg.close();
			}
		}
	}

	/// Close, then remove the durable subscriptions from the broker.
	///
	/// Only for a subscription that is genuinely going away. Calling this on a
	/// redeploy would discard everything the broker held while the app was
	/// down, which is the one thing durability is for.
	public void unsubscribe() {
		closed = true;
		DistributedMembers.unregister(membership);
		membership = null;
		for (Leg leg : legs.values()) {
			leg.unsubscribe();
		}
		closeAll();
	}
}
