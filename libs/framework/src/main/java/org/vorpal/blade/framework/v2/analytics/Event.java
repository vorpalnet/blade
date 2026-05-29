package org.vorpal.blade.framework.v2.analytics;

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

/// Persistent class for the `event` database table.
///
/// The `name` field is wire-only (`@Transient` for JPA, still serialized
/// over JMS by Java Serialization). The consumer translates it to
/// `event_type_id` via the [EventType] lookup table before persist.
///
/// Attributes are held in a transient `Map<String, Attribute>` at the
/// wire level; the consumer persists them explicitly with the resolved
/// `event_id` and `attribute_name_id` rather than relying on JPA's
/// cascade.
@Entity
@Table(name = "event")
@NamedQuery(name = "Event.findAll", query = "SELECT e FROM Event e")
@JsonPropertyOrder({ "name", "attributes", "id", "application_id", "sessionId", "created" })
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

	/// Wire-side event-name string (e.g. "callStarted"). Not stored;
	/// translated to [#eventTypeId] by the consumer.
	@Transient
	private String name;

	/// Wire-side attribute collection. Not persisted via JPA cascade —
	/// the consumer iterates these and persists each [Attribute]
	/// explicitly after the event row is inserted and `event_id` is
	/// known.
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

	public Map<String, Attribute> getAttributes() {
		return this.attributes;
	}

	public void setAttributes(Map<String, Attribute> attributes) {
		this.attributes = attributes;
	}
}
