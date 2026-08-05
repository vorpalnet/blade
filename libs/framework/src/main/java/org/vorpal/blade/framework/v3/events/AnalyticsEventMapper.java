package org.vorpal.blade.framework.v3.events;

import java.util.Date;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Builds the CloudEvents the framework's own analytics publishes.
///
/// Deliberately a **pure function of its arguments** — no JMS, no JNDI, no
/// `SettingsManager` — so every envelope this system emits can be checked against
/// its declared schema in a plain JVM. `AnalyticsEventMapperTest` does exactly
/// that, which is the only thing standing between the payloads and the schemas
/// drifting apart.
///
/// This was briefly a *translation* layer, turning JPA entities the producer had
/// filled in back into events. That is gone: the producer states facts now, and
/// this is where a fact is shaped. Three things the old wire format got wrong,
/// fixed here rather than carried forward:
///
/// 1. **Start and stop become distinct types.** On the old wire a `Session`
///    start and stop are the same class told apart by whether `destroyed` is
///    null. Every consumer had to infer intent from a null field.
/// 2. **`SessionKey` stops smuggling.** The old object carries the vorpal-id in
///    the primary key's `session_id` slot and the consumer rewrites it to the
///    database key. Here the correlator is in `subject` where it belongs and
///    `session_id` never appears on the wire at all.
/// 3. **`time` is publish time; domain times are fields.** The X-Vorpal-ID `ts`
///    records when the *Vorpal-ID* was created — the birth of the call — not
///    when an individual event happened. Collapsing them would stamp every event
///    in a call with the same instant.
///
/// The correlator is `vorpalId` **plus** the call's birth instant, because a
/// Vorpal-ID is 32 bits and is only checked for uniqueness among *live*
/// sessions: ids are reused, so the pair is an identity where the id alone is
/// only a correlator.
public final class AnalyticsEventMapper {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AnalyticsEventMapper() {
	}

	/// The CloudEvents `subject` for a call: `<vorpalIdHex>.<tsHex>`.
	///
	/// Both halves are already on the SIP wire. A consumer that only wants the
	/// id splits on the dot.
	public static String subject(long vorpalId, Date created) {
		String id = String.format("%08X", vorpalId);
		return (created == null) ? id : id + "." + Long.toHexString(created.getTime());
	}

	/// `application.started` / `application.stopped`.
	public static CloudEvent application(String source, String appName, String domain, String server, String host,
			String tenant, Date started, Date stopped) {

		ObjectNode data = MAPPER.createObjectNode();
		data.put("appName", appName);
		data.put("domain", domain);
		data.put("server", server);
		put(data, "host", host);
		put(data, "tenant", tenant);
		put(data, "appStartedAt", started);

		String type = BladeEventTypes.APPLICATION_STARTED;
		if (stopped != null) {
			type = BladeEventTypes.APPLICATION_STOPPED;
			put(data, "stoppedAt", stopped);
		}
		// No subject: application lifecycle is not call-scoped.
		return CloudEvent.create(type, source, null, data, BladeEventCatalog.versionOf(type));
	}

	/// `session.started` / `session.stopped`.
	public static CloudEvent session(String source, long vorpalId, Date created, Date destroyed, String appName,
			String domain, String server, Date appStarted) {

		ObjectNode data = MAPPER.createObjectNode();
		data.put("vorpalId", String.format("%08X", vorpalId));
		put(data, "startedAt", created);
		data.put("appName", appName);
		data.put("domain", domain);
		data.put("server", server);
		put(data, "appStartedAt", appStarted);

		String type = BladeEventTypes.SESSION_STARTED;
		if (destroyed != null) {
			type = BladeEventTypes.SESSION_STOPPED;
			put(data, "stoppedAt", destroyed);
		}
		return CloudEvent.create(type, source, subject(vorpalId, created), data, BladeEventCatalog.versionOf(type));
	}

	/// `session.key` — an index key attached to a call.
	///
	/// Note what is absent: no `session_id`. The old wire object put the
	/// vorpal-id in that slot and the consumer overwrote it with the database
	/// key, one field carrying two meanings with a silent rewrite between them.
	///
	/// **The application identity is not decoration.** A session key may be the
	/// first thing a consumer sees for a call, so it has to be able to resolve the
	/// session — and a session row carries a `NOT NULL` foreign key to the
	/// application. Without these four fields the consumer had nothing to resolve
	/// it with, and the old path papered over that by inserting a stub session
	/// with `application_id = 0`, which violates the constraint unless a row with
	/// id 0 happens to exist.
	public static CloudEvent sessionKey(String source, long vorpalId, Date created, String name, String value,
			String appName, String domain, String server, Date appStarted) {

		ObjectNode data = MAPPER.createObjectNode();
		data.put("vorpalId", String.format("%08X", vorpalId));
		put(data, "startedAt", created);
		data.put("appName", appName);
		data.put("domain", domain);
		data.put("server", server);
		put(data, "appStartedAt", appStarted);
		data.put("name", name);
		data.put("value", value);
		return CloudEvent.create(BladeEventTypes.SESSION_KEY, source, subject(vorpalId, created), data,
				BladeEventCatalog.versionOf(BladeEventTypes.SESSION_KEY));
	}

	/// A named analytics event, with its attributes flattened from the
	/// entity-attribute-value shape into a plain array of pairs.
	///
	/// **The type depends on the name.** One of the framework's eleven when
	/// `eventName` is one BLADE emits itself, so a consumer can select precisely
	/// on `eventType`; [BladeEventTypes#CALL_EVENT] otherwise, carrying the name
	/// in the payload — which is what keeps an operator's own names in an app's
	/// `analytics.events` configuration flowing without a catalog edit.
	///
	/// `eventName` stays in the payload either way. It costs one field and it
	/// means a consumer that has the envelope but not the catalog can still say
	/// what happened.
	///
	/// @param vorpalId null for a genuinely sessionless event
	public static CloudEvent callEvent(String source, String eventName, Long vorpalId, Date created, Date occurredAt,
			String appName, String domain, String server, Date appStarted,
			java.util.Map<String, String> attributes) {

		ObjectNode data = MAPPER.createObjectNode();
		data.put("eventName", eventName);
		put(data, "occurredAt", occurredAt);
		data.put("appName", appName);
		data.put("domain", domain);
		data.put("server", server);
		put(data, "appStartedAt", appStarted);

		if (vorpalId != null) {
			data.put("vorpalId", String.format("%08X", vorpalId.longValue()));
			put(data, "startedAt", created);
		}

		com.fasterxml.jackson.databind.node.ArrayNode array = data.putArray("attributes");
		if (attributes != null) {
			for (java.util.Map.Entry<String, String> entry : attributes.entrySet()) {
				ObjectNode pair = array.addObject();
				pair.put("name", entry.getKey());
				pair.put("value", entry.getValue());
			}
		}

		String subject = (vorpalId == null) ? null : subject(vorpalId.longValue(), created);
		String type = BladeEventTypes.forEventName(eventName);
		return CloudEvent.create(type, source, subject, data, BladeEventCatalog.versionOf(type));
	}

	private static void put(ObjectNode node, String field, String value) {
		if (value != null && !value.isEmpty()) {
			node.put(field, value);
		}
	}

	/// Domain times go on the wire as ISO-8601 instants, never as epoch
	/// millis — a consumer in another language should not have to know which
	/// epoch or which precision.
	private static void put(ObjectNode node, String field, Date value) {
		if (value != null) {
			node.put(field, java.time.format.DateTimeFormatter.ISO_INSTANT
					.format(java.time.Instant.ofEpochMilli(value.getTime())));
		}
	}
}
