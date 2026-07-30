package org.vorpal.blade.services.events;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.InitialContext;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventPublisher;
import org.vorpal.blade.framework.v3.events.EventType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Watches live events on a topic without disturbing anything else consuming it.
///
/// **Why this lives on the engine tier and not in the console.** A tap needs a
/// real JMS consumer, and the connection factory and destination are targeted at
/// the engine cluster. Running it from the AdminServer would mean a remote t3
/// JMS client from a tier that has no business in the data path.
///
/// **Why a non-durable subscriber.** A distributed topic gives every subscriber
/// its own copy, so a non-durable subscriber sees what is published while it is
/// connected, retains nothing, creates no subscription state, and consumes
/// nobody else's copy. It is invisible to the durable consumers that matter.
/// Deliberately *not*
/// `JMSDestinationRuntimeMBean.createDurableSubscriber` — that would create
/// persistent subscription state that keeps accruing messages after the browser
/// tab closes and shows up in the subscriber list forever.
///
/// **This is a wiretap, and it is treated as one.** Events carry `subject` — the
/// call correlator — and whatever the producer put in `data`. Fields the catalog
/// marks sensitive are masked before anything leaves this method, every tap is
/// logged with its principal and selector, and the window is capped server-side
/// regardless of what the caller asks for.
///
/// Queues are not tappable: a consuming tap on a queue would steal messages from
/// the real consumer. Browse them from the console instead — that reads what is
/// at rest and takes nothing.
@Path("/tap")
public class EventTapResource {

	private static final Logger logger = Logger.getLogger(EventTapResource.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// Hard ceilings. A caller asking for more gets these — a tap is a
	/// diagnostic, not a feed.
	private static final int MAX_SECONDS = 60;
	private static final int MAX_MESSAGES = 500;

	private static final String MASKED = "***";

	@Context
	private ServletContext servletContext;

	@Context
	private javax.servlet.http.HttpServletRequest request;

	/// Collect events for a bounded window and return them.
	///
	/// @param type    an event type to filter on, or null for everything
	/// @param subject a call correlator to filter on, or null
	/// @param seconds how long to listen, capped at [#MAX_SECONDS]
	/// @param max     how many to collect, capped at [#MAX_MESSAGES]
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response tap(@QueryParam("type") String type, @QueryParam("subject") String subject,
			@QueryParam("seconds") int seconds, @QueryParam("max") int max) {

		EventCatalog catalog = currentCatalog();
		if (catalog == null) {
			return error("no event catalog on this node");
		}

		int window = clamp(seconds, 10, MAX_SECONDS);
		int limit = clamp(max, 50, MAX_MESSAGES);
		String selector = selectorFor(type, subject);

		logger.info("event tap by " + principal() + " on " + catalog.getDefaultDestinationJndi()
				+ ((selector == null) ? " (everything)" : " selector " + selector)
				+ " for " + window + "s");

		Connection connection = null;
		Session session = null;
		MessageConsumer consumer = null;
		try {
			InitialContext ctx = new InitialContext();
			ConnectionFactory factory = (ConnectionFactory) ctx.lookup(catalog.getConnectionFactoryJndi());
			Destination destination = (Destination) ctx.lookup(catalog.getDefaultDestinationJndi());

			connection = factory.createConnection();
			session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			// Non-durable, by construction: no subscription name, no client id.
			consumer = (selector == null) ? session.createConsumer(destination)
					: session.createConsumer(destination, selector);
			connection.start();

			ArrayNode collected = MAPPER.createArrayNode();
			long deadline = System.currentTimeMillis() + (window * 1000L);
			while (collected.size() < limit) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					break;
				}
				Message message = consumer.receive(Math.min(remaining, 1000L));
				if (message == null) {
					continue;
				}
				ObjectNode row = describe(message, catalog);
				if (row != null) {
					collected.add(row);
				}
			}

			ObjectNode result = MAPPER.createObjectNode();
			result.put("destination", catalog.getDefaultDestinationJndi());
			result.put("selector", (selector == null) ? "" : selector);
			result.put("seconds", window);
			result.set("events", collected);
			return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();

		} catch (Exception e) {
			return error(String.valueOf(e));
		} finally {
			close(consumer);
			close(session);
			close(connection);
		}
	}

	/// Turn one message into a row, masking anything the catalog marks
	/// sensitive.
	private ObjectNode describe(Message message, EventCatalog catalog) {
		try {
			ObjectNode row = MAPPER.createObjectNode();
			row.put("eventType", message.getStringProperty(EventPublisher.PROP_TYPE));
			row.put("eventSubject", message.getStringProperty(EventPublisher.PROP_SUBJECT));
			row.put("eventId", message.getStringProperty(EventPublisher.PROP_ID));
			row.put("received", System.currentTimeMillis());

			if (!(message instanceof TextMessage)) {
				row.put("body", "(non-text message: " + message.getClass().getName() + ")");
				return row;
			}

			CloudEvent event = CloudEvent.fromJson(((TextMessage) message).getText());
			row.put("type", event.getType());
			row.put("subject", event.getSubject());
			row.put("time", event.getTime());
			row.put("source", event.getSource());
			row.set("data", mask(event.getData(), catalog.findType(event.getType())));
			return row;
		} catch (Exception e) {
			ObjectNode row = MAPPER.createObjectNode();
			row.put("error", "unreadable message: " + e);
			return row;
		}
	}

	/// Replace the values of fields the declaration marks sensitive.
	///
	/// The cheap version of privacy, and the one that matters: the common reason
	/// to tap is "are events flowing at all", which needs the shape and not the
	/// contents.
	private JsonNode mask(JsonNode data, EventType declaration) {
		if (data == null || !data.isObject() || declaration == null) {
			return data;
		}
		List<String> sensitive = declaration.sensitiveFieldsOrEmpty();
		if (sensitive.isEmpty()) {
			return data;
		}
		ObjectNode copy = ((ObjectNode) data).deepCopy();
		for (String field : sensitive) {
			if (copy.has(field)) {
				copy.put(field, MASKED);
			}
		}
		return copy;
	}

	/// Build a JMS selector from the query parameters, using the same property
	/// names the publisher stamps — so a tap filters exactly the way a real
	/// consumer would.
	private static String selectorFor(String type, String subject) {
		List<String> clauses = new ArrayList<>();
		if (type != null && !type.isEmpty()) {
			clauses.add(EventPublisher.PROP_TYPE + " = '" + escape(type) + "'");
		}
		if (subject != null && !subject.isEmpty()) {
			clauses.add(EventPublisher.PROP_SUBJECT + " = '" + escape(subject) + "'");
		}
		return clauses.isEmpty() ? null : String.join(" AND ", clauses);
	}

	/// A single quote is the escape for a single quote in a JMS selector
	/// literal. Without this a crafted parameter could change the selector's
	/// meaning.
	private static String escape(String value) {
		return value.replace("'", "''");
	}

	private static int clamp(int value, int fallback, int ceiling) {
		if (value <= 0) {
			return fallback;
		}
		return Math.min(value, ceiling);
	}

	private String principal() {
		String user = (request == null) ? null : request.getRemoteUser();
		return (user == null) ? "<unauthenticated>" : user;
	}

	@SuppressWarnings("unchecked")
	private EventCatalog currentCatalog() {
		Object attr = (servletContext == null) ? null
				: servletContext.getAttribute(EventBusStartup.CATALOG_SUPPLIER_ATTR);
		if (attr instanceof Supplier) {
			Object candidate = ((Supplier<Object>) attr).get();
			if (candidate instanceof EventCatalog) {
				return (EventCatalog) candidate;
			}
		}
		return null;
	}

	private static void close(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception ignore) {
			// A tap that cannot tidy up must not fail the response it already
			// produced.
		}
	}

	private static Response error(String message) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("error", message);
		return Response.status(Response.Status.SERVICE_UNAVAILABLE).type(MediaType.APPLICATION_JSON)
				.entity(body.toString()).build();
	}
}
