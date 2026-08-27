package org.vorpal.blade.framework.v3.events;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.vorpal.blade.framework.v3.metrics.Counter;

/// Publishes [CloudEvent]s to an event-bus destination as JSON `TextMessage`s.
///
/// **The only publisher.** Analytics used to have a second one of its own,
/// writing Java-serialized JPA entities to a queue — which meant a consumer had
/// to be on BLADE's classpath to read a message at all, and had to discriminate
/// by `instanceof` because nothing on the wire said what a message was. Here the
/// payload is JSON in a `TextMessage`, and the `type`, `subject` and `id`
/// attributes are copied into JMS string properties so a consumer can select on
/// them without parsing the body. Analytics now publishes through this like
/// everything else.
///
/// **Topics and queues.** This uses the JMS 1.1 unified domain
/// ([Session]/[MessageProducer]/[Destination]) rather than the topic-specific
/// interfaces, so one code path serves both. The catalog decides which a given
/// event type gets; nothing here needs to know.
///
/// **Why a bounded session pool.** A JMS `Session` must not be used by two
/// threads at once, so a publisher needs more than one under load. The obvious
/// implementation — cache a session per calling thread — is what this class did
/// originally, and it leaks: WebLogic's self-tuning thread pool creates and
/// retires threads over the life of the server, and a map keyed by `Thread`
/// keeps a session (a real server-side resource) alive for every thread that
/// ever published, forever.
///
/// A bounded free-list makes that structurally impossible instead of policing
/// it. A publish borrows a sender, uses it, and returns it; if the pool is full
/// on return the sender is simply closed. At most [#DEFAULT_POOL_SIZE] senders
/// are ever retained regardless of how many threads pass through, and a burst
/// wider than the pool degrades to create-use-close rather than failing or
/// blocking.
///
/// A borrowed sender is only ever used by one thread at a time — the queue hands
/// it to exactly one borrower and establishes the happens-before edge before it
/// is touched again. Note that the JMS specification forbids *concurrent* use of
/// a session; I have no citation that explicitly blesses serial handoff between
/// threads, which is what this does and what session pools generally do. If that
/// turns out to be a problem on OCCAS, the fix is a pool size of zero — every
/// publish creating and closing its own session — which this already degrades to
/// naturally.
public class EventPublisher {

	/// JMS string-property name carrying the CloudEvents `type` — the primary
	/// message-selector key (e.g. `eventType = 'net.vorpal.attendant.meeting.scheduled'`).
	/// [EventType#selector()] builds selectors from this constant so a consumer's
	/// filter cannot disagree with what is actually stamped.
	public static final String PROP_TYPE = "eventType";

	/// JMS string-property name carrying the CloudEvents `subject` (the Vorpal
	/// session id) — the per-call correlation selector.
	public static final String PROP_SUBJECT = "eventSubject";

	/// JMS string-property name carrying the CloudEvents `id`, for consumer-side
	/// idempotency / dedupe.
	public static final String PROP_ID = "eventId";

	/// JMS int-property name carrying the CloudEvents `dataversion` extension —
	/// the [EventType#getVersion] the producer was generated against. Absent when
	/// the producer predates versioning or the type is undeclared.
	public static final String PROP_VERSION = "eventVersion";

	/// How many sessions a publisher retains between sends. Sized for the number
	/// of threads realistically publishing at once on one engine node, not for
	/// the number of threads that exist.
	public static final int DEFAULT_POOL_SIZE = 16;

	private final String connectionFactoryJndi;
	private final String destinationJndi;
	private final BlockingQueue<Sender> pool;

	private ConnectionFactory factory;
	private Connection connection;
	private Destination destination;
	private volatile boolean closed;

	/// Optional publish meters — see [#meter]. Null when nobody is counting.
	private volatile Counter.Series published;
	private volatile Counter.Series failed;

	/// A session and its producer, borrowed and returned as one unit.
	private final class Sender {
		private final Session jmsSession;
		private final MessageProducer producer;

		private Sender(Session jmsSession, MessageProducer producer) {
			this.jmsSession = jmsSession;
			this.producer = producer;
		}

		private void close() {
			try {
				producer.close();
			} catch (JMSException e) {
				// Nothing useful to do: we are discarding this sender anyway.
			}
			try {
				jmsSession.close();
			} catch (JMSException e) {
				// Same.
			}
		}
	}

	/// @param connectionFactoryJndi the connection factory JNDI name (typically
	///                              [EventBus#CONNECTION_FACTORY_JNDI])
	/// @param destinationJndi       the topic or queue JNDI name (typically
	///                              [EventBus#TOPIC_JNDI])
	public EventPublisher(String connectionFactoryJndi, String destinationJndi) {
		this(connectionFactoryJndi, destinationJndi, DEFAULT_POOL_SIZE);
	}

	/// @param connectionFactoryJndi the connection factory JNDI name
	/// @param destinationJndi       the topic or queue JNDI name
	/// @param poolSize              how many sessions to retain between sends;
	///                              zero means create and close one per publish
	public EventPublisher(String connectionFactoryJndi, String destinationJndi, int poolSize) {
		this.connectionFactoryJndi = connectionFactoryJndi;
		this.destinationJndi = destinationJndi;
		this.pool = (poolSize > 0) ? new ArrayBlockingQueue<Sender>(poolSize) : null;
	}

	/// The destination this publisher sends to — the key the bus registers it
	/// under.
	public String getDestinationJndi() {
		return destinationJndi;
	}

	/// Wire the publish meters — pre-resolved [Counter.Series] handles, so
	/// counting costs one `LongAdder` increment on the SIP container thread.
	///
	/// Optional: an unmetered publisher publishes exactly the same, it just is
	/// not counted. The failure count is the one that matters — a failed send is
	/// a **dropped event** (the quota-full scenario), and without this the only
	/// trace was a warning line in a log nobody reads.
	///
	/// @param published incremented once per successful send
	/// @param failed    incremented once per send that threw
	public void meter(Counter.Series published, Counter.Series failed) {
		this.published = published;
		this.failed = failed;
	}

	/// Look up the connection factory and destination, open the shared
	/// connection, and start delivery. Sessions are created on demand.
	///
	/// @throws NamingException if a JNDI lookup fails
	/// @throws JMSException    if the connection cannot be created or started
	public void init() throws NamingException, JMSException {
		InitialContext ctx = new InitialContext();
		factory = (ConnectionFactory) ctx.lookup(connectionFactoryJndi);
		destination = (Destination) ctx.lookup(destinationJndi);
		connection = factory.createConnection();
		connection.start();
	}

	private Sender borrow() throws JMSException {
		if (pool != null) {
			Sender pooled = pool.poll();
			if (pooled != null) {
				return pooled;
			}
		}
		Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
		return new Sender(session, session.createProducer(destination));
	}

	/// Return a sender to the pool, or close it when the pool is full or the
	/// publisher has since shut down. Never throws — a failure to recycle must
	/// not turn a successful publish into an error.
	private void release(Sender sender) {
		if (closed || pool == null || !pool.offer(sender)) {
			sender.close();
		}
	}

	/// Publish a CloudEvent. The envelope is serialized to JSON and sent as a
	/// `TextMessage`; `type`, `subject`, `id`, and `dataversion` are copied into
	/// JMS properties for selector-based routing and version checks.
	///
	/// @param event the event to publish
	/// @throws JMSException        if the send fails
	/// @throws java.io.IOException if the envelope cannot be serialized
	public void publish(CloudEvent event) throws JMSException, java.io.IOException {
		try {
			String json = event.toJson();
			Sender sender = borrow();
			try {
				TextMessage message = sender.jmsSession.createTextMessage(json);
				if (event.getType() != null) {
					message.setStringProperty(PROP_TYPE, event.getType());
				}
				if (event.getSubject() != null) {
					message.setStringProperty(PROP_SUBJECT, event.getSubject());
				}
				if (event.getId() != null) {
					message.setStringProperty(PROP_ID, event.getId());
				}
				if (event.getDataversion() != null) {
					message.setIntProperty(PROP_VERSION, event.getDataversion().intValue());
				}
				sender.producer.send(message);
			} finally {
				release(sender);
			}
		} catch (JMSException | java.io.IOException | RuntimeException e) {
			Counter.Series counter = failed;
			if (counter != null) {
				counter.increment();
			}
			throw e;
		}
		Counter.Series counter = published;
		if (counter != null) {
			counter.increment();
		}
	}

	/// Publish a raw CloudEvents JSON envelope — the ingress path, where bytes
	/// arrive over HTTP and are republished. Parses the envelope to populate the
	/// routing properties.
	///
	/// @param json a CloudEvents 1.0 JSON envelope
	/// @throws JMSException        if the send fails
	/// @throws java.io.IOException if the JSON is malformed
	public void publish(String json) throws JMSException, java.io.IOException {
		publish(CloudEvent.fromJson(json));
	}

	/// Close every pooled session and the shared connection. Senders currently
	/// borrowed by an in-flight publish close themselves on release, because
	/// [#release] sees `closed`.
	public void close() {
		closed = true;
		if (pool != null) {
			Sender sender = pool.poll();
			while (sender != null) {
				sender.close();
				sender = pool.poll();
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
}
