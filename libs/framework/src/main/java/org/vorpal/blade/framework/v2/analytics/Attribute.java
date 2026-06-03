package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Transient;

/// Persistent class for the `attribute` database table.
///
/// The `name` field is wire-only (`@Transient` for JPA; still serialized
/// over JMS by Java Serialization) — the consumer translates it to
/// `attribute_name_id` via the [AttributeName] lookup table before persist.
@Entity
@Table(name = "attributes")
@NamedQuery(name = "Attribute.findAll", query = "SELECT a FROM Attribute a")
public class Attribute implements Serializable {
	private static final long serialVersionUID = 1L;

	@EmbeddedId
	private AttributePK id;

	@Column(nullable = false, length = 1024)
	private String value;

	/// Wire-side string name (e.g. "From", "To", "caller"). Not stored;
	/// translated to [AttributePK#attributeNameId] by the consumer.
	@Transient
	private String name;

	public Attribute() {
	}

	public Attribute(String name, String value) {
		this.name = name;
		this.id = new AttributePK();
		this.value = value;
	}

	public AttributePK getId() {
		return this.id;
	}

	public void setId(AttributePK id) {
		this.id = id;
	}

	public String getValue() {
		return this.value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
