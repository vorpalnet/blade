package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/// Lookup row for normalized attribute-name strings. The hot `attribute`
/// table stores a 2-byte `attribute_name_id` instead of a repeated 64-char
/// name.
@Entity
@Table(name = "attribute_name")
@NamedQuery(name = "AttributeName.findByName", query = "SELECT n FROM AttributeName n WHERE n.name = :name")
public class AttributeName implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(updatable = false, nullable = false)
	private short id;

	@Column(unique = true, nullable = false, length = 64)
	private String name;

	public AttributeName() {
	}

	public AttributeName(String name) {
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
