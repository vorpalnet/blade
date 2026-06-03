package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/// Lookup row for normalized event-name strings. The hot `event` table
/// stores a 2-byte `event_type_id` instead of a repeated 64-char name.
@Entity
@Table(name = "event_types")
@NamedQuery(name = "EventType.findByName", query = "SELECT t FROM EventType t WHERE t.name = :name")
public class EventType implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(updatable = false, nullable = false)
	private short id;

	@Column(unique = true, nullable = false, length = 64)
	private String name;

	public EventType() {
	}

	public EventType(String name) {
		this.name = name;
	}

	public short getId() {
		return id;
	}

	public void setId(short id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
