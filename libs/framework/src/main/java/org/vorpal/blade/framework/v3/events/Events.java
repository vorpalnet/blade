package org.vorpal.blade.framework.v3.events;

import java.util.Date;
import java.util.function.Consumer;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// How application code puts a fact on the event bus.
///
/// ```java
/// Events.publish(app, BladeEventTypes.CALL_UTTERANCE, data -> data
///         .put("text", text)
///         .put("party", party)
///         .put("startMs", startMs));
/// ```
///
/// The caller names the type and states the event's own facts, typed. This
/// class supplies everything else, so no two producers can spell it
/// differently: the correlator and the call's birth instant from the
/// application session, the CloudEvents `source` and `subject`, the publishing
/// application's identity, and the declared `dataversion`. The payload is one
/// flat object; see [AnalyticsEventMapper#callScoped] for its shape. A null
/// field is dropped, so an optional value needs no test first.
///
/// **It never throws.** It runs on the SIP container thread and on media
/// callbacks, where a lost event must not cost a call. A failed send is logged
/// with the type it dropped. A bus that is not configured on this node is
/// logged once, then again after each recovery, because the silent no-op is
/// what made a missing publisher so hard to see. The `boolean` result says
/// whether the event left this node, for the rare caller that reports it.
///
/// [EventBus] stays underneath for the one producer that must see the
/// failure: the HTTP ingress, which answers its client with it.
public final class Events {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final java.util.logging.Logger FALLBACK = java.util.logging.Logger
			.getLogger(Events.class.getName());

	/// Whether the "bus not configured" warning is owed. Cleared when it is
	/// logged, set again by the next successful send, so each outage is reported
	/// once rather than once per event.
	private static volatile boolean warnUnready = true;

	private Events() {
	}

	/// Publish a call-scoped event, correlated through the call's application
	/// session.
	///
	/// @param app    the call's application session; null publishes a
	///               sessionless event
	/// @param type   the CloudEvents type, one of [BladeEventTypes]
	/// @param fields sets the event's own facts; may be null
	/// @return true if the event was sent
	public static boolean publish(SipApplicationSession app, String type, Consumer<ObjectNode> fields) {
		return publish(app, type, null, fields);
	}

	/// As [#publish(SipApplicationSession, String, Consumer)], for an
	/// application's own declared type.
	///
	/// @param version the declaration revision this producer was written
	///                against, its generated payload class's `VERSION`. The
	///                framework's types need none: their code and their
	///                declarations ship together.
	public static boolean publish(SipApplicationSession app, String type, Integer version,
			Consumer<ObjectNode> fields) {
		try {
			Long vorpalId = (app == null) ? null : Analytics.getVorpalId(app);
			Date startedAt = (vorpalId == null) ? null : Analytics.getCallStartedAt(app);
			return publish(vorpalId, startedAt, type, version, fields);
		} catch (Throwable t) {
			dropped(type, t);
			return false;
		}
	}

	/// Publish a call-scoped event from a thread that holds the correlator but
	/// not the application session: a timer, a media callback, an HTTP request
	/// about a call the application tracks itself.
	///
	/// @param vorpalId  the call's correlator, or null for a sessionless event
	/// @param startedAt the call's birth instant. Identity, not a report time:
	///                  it must be the value the call's other events carry
	public static boolean publish(Long vorpalId, Date startedAt, String type, Consumer<ObjectNode> fields) {
		return publish(vorpalId, startedAt, type, null, fields);
	}

	/// As [#publish(Long, Date, String, Consumer)], for an application's own
	/// declared type.
	///
	/// @param version the declaration revision this producer was written
	///                against, its generated payload class's `VERSION`
	public static boolean publish(Long vorpalId, Date startedAt, String type, Integer version,
			Consumer<ObjectNode> fields) {
		try {
			ObjectNode data = MAPPER.createObjectNode();
			if (fields != null) {
				fields.accept(data);
			}
			CloudEvent event = AnalyticsEventMapper.callScoped(source(), type, vorpalId, startedAt, new Date(),
					SettingsManager.getApplicationName(), SettingsManager.getDomainName(),
					SettingsManager.getServerName(), Analytics.getApplicationStartedAt(), data);
			if (version != null) {
				event.setDataversion(version);
			}
			return publish(event);
		} catch (Throwable t) {
			dropped(type, t);
			return false;
		}
	}

	/// Publish an event that is not about one call: a room's membership, a
	/// recording closing, a browser's message relayed from a web session. The
	/// payload is exactly what `fields` sets, less its nulls.
	///
	/// @param subject the CloudEvents `subject`, the key a consumer correlates
	///                on; may be null
	public static boolean publish(String type, String subject, Consumer<ObjectNode> fields) {
		return publish(type, null, subject, fields);
	}

	/// As [#publish(String, String, Consumer)], for an application's own
	/// declared type.
	///
	/// @param version the declaration revision this producer was written
	///                against, its generated payload class's `VERSION`
	public static boolean publish(String type, Integer version, String subject, Consumer<ObjectNode> fields) {
		try {
			ObjectNode data = MAPPER.createObjectNode();
			if (fields != null) {
				fields.accept(data);
			}
			data.remove(nullFields(data));
			return publish(CloudEvent.create(type, source(), subject, data,
					(version != null) ? version : BladeEventCatalog.versionOf(type)));
		} catch (Throwable t) {
			dropped(type, t);
			return false;
		}
	}

	/// Publish an envelope built elsewhere (a relayed event, or one a mapper
	/// shaped) to the default destination.
	public static boolean publish(CloudEvent event) {
		if (event == null) {
			return false;
		}
		if (!EventBus.isReady()) {
			unready(event.getType());
			return false;
		}
		try {
			EventBus.publish(event);
			warnUnready = true;
			return true;
		} catch (Throwable t) {
			dropped(event.getType(), t);
			return false;
		}
	}

	/// The CloudEvents `source` this application publishes as: the `events`
	/// configuration's own value, else `/blade/<application name>`.
	public static String source() {
		EventBusSettings settings = SettingsManager.getEventBus();
		if (settings != null && settings.getSource() != null && !settings.getSource().isEmpty()) {
			return settings.getSource();
		}
		return "/blade/" + SettingsManager.getApplicationName();
	}

	private static java.util.List<String> nullFields(ObjectNode data) {
		java.util.List<String> nulls = new java.util.ArrayList<>();
		data.fields().forEachRemaining(field -> {
			if (field.getValue() == null || field.getValue().isNull()) {
				nulls.add(field.getKey());
			}
		});
		return nulls;
	}

	private static void unready(String type) {
		if (!warnUnready || EventBus.isQuiet()) {
			// Off, or never provisioned: nobody expects these events, so a
			// miss is not news.
			return;
		}
		warnUnready = false;
		warning("events: no publisher on this node for " + EventBus.getDefaultDestinationJndi() + "; " + type
				+ " and every event after it are not leaving this node until the event bus is enabled in this "
				+ "application's configuration.");
	}

	private static void dropped(String type, Throwable t) {
		warning("events: " + type + " DROPPED, not published to " + EventBus.getDefaultDestinationJndi() + ": "
				+ t.getClass().getSimpleName() + ": " + t.getMessage());
	}

	/// The SIP logger when there is one. An application with no SIP servlet,
	/// or one whose WebSocket container starts first, publishes before it
	/// exists, and a report must never be what fails.
	private static void warning(String message) {
		org.vorpal.blade.framework.v2.logging.Logger sip = SettingsManager.getSipLogger();
		if (sip != null) {
			sip.warning(message);
		} else {
			FALLBACK.warning(message);
		}
	}
}
