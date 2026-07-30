package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/// One analytics fact, while it is still being assembled.
///
/// **Why this exists rather than a `CloudEvent` directly.** An analytics event is
/// built in two passes at two different points in a callflow: the *origin*
/// attributes are extracted when the event is created, and the *destination*
/// attributes only when the message that carries them is finally sent. Between
/// those two moments the event rides on the SIP message as an attribute. A
/// `CloudEvent` is deliberately immutable — it is what goes on the wire — so
/// something has to hold the half-built fact in the meantime. This is it, and
/// [#toCloudEvent] is the one-way door at the end.
///
/// **Why this replaced the JPA `Event` entity.** That is the shape the producer
/// used to fill in: a row, with a generated primary key, an `event_type_id`, a
/// `session_id` and an `application_id` — three surrogate keys that only the
/// consumer's database knows how to assign — plus three `@Transient` fields
/// carrying the real wire data past the columns that shadowed them. The producer
/// was writing a database row it had no ability to key, and every consumer
/// therefore had to be a database. A producer states a fact; where it is stored,
/// and whether it is stored at all, is the subscriber's business.
///
/// Not a JMS payload and not persistable: this never leaves the publishing JVM.
/// It is `Serializable` only because it is stashed on a `SipServletMessage`
/// attribute, which a clustered container may replicate.
public class AnalyticsEvent implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String name;
	private final Date occurredAt;

	private Long vorpalId;
	private Date callStartedAt;

	/// Insertion-ordered so the attributes reach the wire in the order the
	/// selectors matched them — origin first, then destination. A consumer must
	/// not depend on that, but a human reading a log line benefits from it.
	private final Map<String, String> attributes = new LinkedHashMap<>();

	/// @param name          the analytics event name, e.g. `callStarted`
	/// @param vorpalId      the call correlator, or null for an event with no call
	/// @param callStartedAt when the call began — *not* when this event happened
	public AnalyticsEvent(String name, Long vorpalId, Date callStartedAt) {
		this(name, vorpalId, callStartedAt, new Date());
	}

	public AnalyticsEvent(String name, Long vorpalId, Date callStartedAt, Date occurredAt) {
		this.name = name;
		this.vorpalId = vorpalId;
		this.callStartedAt = callStartedAt;
		this.occurredAt = (occurredAt == null) ? new Date() : occurredAt;
	}

	/// Attach the call this event belongs to, when it is only discoverable after
	/// the event was created.
	///
	/// The HTTP path needs this: `AnalyticsFilter` builds the event from the
	/// request body, and only finds the SIP call it belongs to — through
	/// `Analytics.sipServletRequest` — when the response comes back.
	///
	/// **Both halves together, deliberately.** A Vorpal-ID is 32 bits and is only
	/// checked for uniqueness among *live* sessions, so ids are reused: the id
	/// alone is a correlator, and only `(id, callStartedAt)` is an identity. A
	/// bare `setVorpalId` would make it easy to attach half of one — which is
	/// what the HTTP path used to do, producing events whose subject collides
	/// with every earlier call that held the same id.
	public AnalyticsEvent correlateWith(Long vorpalId, Date callStartedAt) {
		this.vorpalId = vorpalId;
		this.callStartedAt = callStartedAt;
		return this;
	}

	/// Attach an extracted attribute. A repeated name overwrites, which is what
	/// the entity-attribute map did before and what the payload's uniqueness rule
	/// requires.
	public AnalyticsEvent addAttribute(String name, String value) {
		if (name != null) {
			attributes.put(name, value);
		}
		return this;
	}

	/// The analytics event name — `callStarted`, or whatever an operator named it
	/// in `analytics.events`.
	public String getName() {
		return name;
	}

	/// The call correlator, or null. Together with [#getCallStartedAt] it
	/// identifies the call durably: a Vorpal-ID is 32 bits and only unique among
	/// *live* sessions, so ids are reused and the id alone is a correlator rather
	/// than an identity.
	public Long getVorpalId() {
		return vorpalId;
	}

	/// When the call began, from the X-Vorpal-ID `ts` parameter.
	public Date getCallStartedAt() {
		return callStartedAt;
	}

	/// When this event happened — distinct from the envelope's `time`, which is
	/// when it was published. Collapsing the two would stamp every event in a call
	/// with whatever instant the publish happened to reach.
	public Date getOccurredAt() {
		return occurredAt;
	}

	/// The extracted attributes, read-only.
	public Map<String, String> getAttributes() {
		return Collections.unmodifiableMap(attributes);
	}

	/// Close the event and produce what goes on the wire.
	///
	/// The CloudEvents `type` follows the name: one of the framework's own eleven
	/// when BLADE emits the name itself, so a consumer can select on it precisely;
	/// [BladeEventTypes#CALL_EVENT] for a name an operator invented, carrying the
	/// name in the payload.
	public CloudEvent toCloudEvent(String source, String appName, String domain, String server, Date appStartedAt) {
		return AnalyticsEventMapper.callEvent(source, name, vorpalId, callStartedAt, occurredAt, appName, domain,
				server, appStartedAt, attributes);
	}

	@Override
	public String toString() {
		return "AnalyticsEvent[" + name + " " + attributes + "]";
	}
}
