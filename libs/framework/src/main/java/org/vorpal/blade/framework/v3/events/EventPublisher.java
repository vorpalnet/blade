package org.vorpal.blade.framework.v3.events;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/// Publishes [CloudEvent]s to the v3 event-bus topic as JSON `TextMessage`s.
///
/// Structurally this mirrors the analytics `JmsPublisher`: the shared
/// [TopicConnection] is created once in [#init()] and reused across threads
/// (Connection objects are thread-safe per the JMS spec), while a separate
/// [TopicSession] + [TopicPublisher] pair is created lazily per calling thread
/// because WebLogic JMS sessions are not thread-safe.
///
/// Two differences from the analytics publisher, both deliberate: it publishes
/// to a **Topic** (pub/sub, fan-out to every subscriber) rather than a Queue,
/// and it sends the CloudEvent as a JSON **`TextMessage`** rather than a
/// Java-serialized `ObjectMessage`, so consumers are not bound to BLADE's
/// classpath. The `type` and `subject` attributes are copied into JMS string
/// properties so a consumer can select on them without parsing the body.
public class EventPublisher {

	/// JMS string-property name carrying the CloudEvents `type` — the primary
	/// message-selector key (e.g. `eventType = 'net.vorpal.attendant.meeting.scheduled'`).
	public static final String PROP_TYPE = "eventType";

	/// JMS string-property name carrying the CloudEvents `subject` (the Vorpal
	/// session id) — the per-call correlation selector.
	public static final String PROP_SUBJECT = "eventSubject";

	/// JMS string-property name carrying the CloudEvents `id`, for consumer-side
	/// idempotency / dedupe.
	public static final String PROP_ID = "eventId";

	private final String connectionFactoryJndi;
	private final String topicJndi;

	private TopicConnectionFactory tconFactory;
	private TopicConnection tcon;
	private Topic topic;

	private final ConcurrentMap<Thread, ThreadResources> threadResources = new ConcurrentHashMap<>();

	private static final class ThreadResources {
		final TopicSession session;
		final TopicPublisher publisher;

		ThreadResources(TopicSession session, TopicPublisher publisher) {
			this.session = session;
			this.publisher = publisher;
		}
	}

	/// @param connectionFactoryJndi the topic connection factory JNDI name
	///                              (typically [EventBus#CONNECTION_FACTORY_JNDI])
	/// @param topicJndi             the topic JNDI name (typically [EventBus#TOPIC_JNDI])
	public EventPublisher(String connectionFactoryJndi, String topicJndi) {
		this.connectionFactoryJndi = connectionFactoryJndi;
		this.topicJndi = topicJndi;
	}

	/// Look up the connection factory and topic, open the shared connection, and
	/// start delivery. Per-thread sessions and publishers are created lazily.
	///
	/// @throws NamingException if a JNDI lookup fails
	/// @throws JMSException    if the connection cannot be created or started
	public void init() throws NamingException, JMSException {
		InitialContext ctx = new InitialContext();
		tconFactory = (TopicConnectionFactory) ctx.lookup(connectionFactoryJndi);
		tcon = tconFactory.createTopicConnection();
		topic = (Topic) ctx.lookup(topicJndi);
		tcon.start();
	}

	private ThreadResources getThreadResources() throws JMSException {
		Thread me = Thread.currentThread();
		ThreadResources tr = threadResources.get(me);
		if (tr == null) {
			TopicSession session = tcon.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
			TopicPublisher publisher = session.createPublisher(topic);
			tr = new ThreadResources(session, publisher);
			ThreadResources existing = threadResources.putIfAbsent(me, tr);
			if (existing != null) {
				publisher.close();
				session.close();
				tr = existing;
			}
		}
		return tr;
	}

	/// Publish a CloudEvent. The envelope is serialized to JSON and sent as a
	/// `TextMessage`; `type`, `subject`, and `id` are copied into JMS string
	/// properties for selector-based routing.
	///
	/// @param event the event to publish
	/// @throws JMSException          if the send fails
	/// @throws java.io.IOException   if the envelope cannot be serialized
	public void publish(CloudEvent event) throws JMSException, java.io.IOException {
		ThreadResources tr = getThreadResources();
		TextMessage message = tr.session.createTextMessage(event.toJson());
		if (event.getType() != null) {
			message.setStringProperty(PROP_TYPE, event.getType());
		}
		if (event.getSubject() != null) {
			message.setStringProperty(PROP_SUBJECT, event.getSubject());
		}
		if (event.getId() != null) {
			message.setStringProperty(PROP_ID, event.getId());
		}
		tr.publisher.publish(message);
	}

	/// Publish a raw CloudEvents JSON envelope — the ingress path, where bytes
	/// arrive over HTTP and are republished verbatim. Parses the envelope to
	/// populate the routing properties.
	///
	/// @param json a CloudEvents 1.0 JSON envelope
	/// @throws JMSException        if the send fails
	/// @throws java.io.IOException if the JSON is malformed
	public void publish(String json) throws JMSException, java.io.IOException {
		publish(CloudEvent.fromJson(json));
	}

	/// Close all per-thread sessions/publishers and the shared connection.
	public void close() {
		for (ThreadResources tr : threadResources.values()) {
			try {
				if (tr.publisher != null) {
					tr.publisher.close();
				}
			} catch (JMSException e) {
				e.printStackTrace();
			}
			try {
				if (tr.session != null) {
					tr.session.close();
				}
			} catch (JMSException e) {
				e.printStackTrace();
			}
		}
		threadResources.clear();

		try {
			if (tcon != null) {
				tcon.close();
			}
		} catch (JMSException e) {
			e.printStackTrace();
		}
	}
}
