package org.vorpal.blade.services.analytics.model;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Transient;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Persistent class for the `events` database table.
///
/// **Nothing here is on the wire any more.** This class used to be the wire
/// format as well as the row: the producer filled it in and Java-serialized it
/// onto a queue, which is why it carried `@Transient` fields shadowing the
/// columns beside them. The wire is CloudEvents now, and this is what the sink
/// writes after resolving the surrogate keys — `event_type_id` through the
/// [EventType] lookup, `session_id` and `application_id` through their natural
/// keys.
///
/// Attributes are persisted explicitly after the event row exists and its
/// `event_id` is known, rather than through a JPA cascade.
@Entity
@Table(name = "events")
@NamedQuery(name = "Event.findAll", query = "SELECT e FROM Event e")
@JsonPropertyOrder({ "name", "attributes", "id", "application_id", "sessionId", "created", "eventUid" })
public class Event implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(updatable = false, unique = true, nullable = false)
	private long id;

	@Column(name = "application_id", nullable = false)
	private long applicationId;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable = false, nullable = false)
	private Date created;

	@Column(name = "event_type_id", nullable = false)
	private short eventTypeId;

	@Column(name = "session_id")
	private Long sessionId;

	/// The CloudEvent `id`, so a redelivery does not become a second row.
	///
	/// **A durable subscription redelivers as a matter of course** — a rolling
	/// restart makes it certain — and `events` has no other natural key to
	/// collide on, so without this a restart silently duplicates rows and
	/// nothing would ever say so. Unique in the schema; nullable, so a row
	/// written by anything that does not carry one is still accepted.
	@Column(name = "event_uid", updatable = false, length = 36)
	private String eventUid;

	/// The event name, resolved to [#eventTypeId] by the sink before persist.
	/// Not a column — kept so a log line can say what the event was.
	@Transient
	private String name;

	/// Attributes, held until the event row exists and its `event_id` is known.
	/// Not persisted via a JPA cascade.
	@Transient
	private Map<String, Attribute> attributes = new HashMap<>();

	public Event() {
		this.setCreated(new Date());
	}

	public Event(long applicationId, long sessionId, String name) {
		this.applicationId = applicationId;
		this.sessionId = sessionId;
		this.name = name;
		this.setCreated(new Date());
	}

	public Event addAttribute(String name, String value) {
		addAttribute(new Attribute(name, value));
		return this;
	}

	public Attribute addAttribute(Attribute attribute) {
		getAttributes().put(attribute.getName(), attribute);
		return attribute;
	}

	public Attribute removeAttribute(Attribute attribute) {
		getAttributes().remove(attribute.getName());
		return attribute;
	}

	public long getId() {
		return this.id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public long getApplicationId() {
		return this.applicationId;
	}

	public void setApplicationId(long applicationId) {
		this.applicationId = applicationId;
	}

	public Date getCreated() {
		return this.created;
	}

	public void setCreated(Date created) {
		this.created = created;
	}

	public short getEventTypeId() {
		return this.eventTypeId;
	}

	public void setEventTypeId(short eventTypeId) {
		this.eventTypeId = eventTypeId;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Long getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(Long sessionId) {
		this.sessionId = sessionId;
	}

	public String getEventUid() {
		return this.eventUid;
	}

	public void setEventUid(String eventUid) {
		this.eventUid = eventUid;
	}

	public Map<String, Attribute> getAttributes() {
		return this.attributes;
	}

	public void setAttributes(Map<String, Attribute> attributes) {
		this.attributes = attributes;
	}
}
