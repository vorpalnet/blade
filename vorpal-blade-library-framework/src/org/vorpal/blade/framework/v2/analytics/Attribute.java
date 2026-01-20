package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Lob;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 * The persistent class for the attribute database table.
 * 
 */
@Entity
@Table(name = "attribute")
@NamedQuery(name = "Attribute.findAll", query = "SELECT a FROM Attribute a")
public class Attribute implements Serializable {
	private static final long serialVersionUID = 1L;

	@EmbeddedId
	private AttributePK id;

	@Lob
	@Column(nullable = false)
	private String value;

	public Attribute() {
	}

	public Attribute(String name, String value) {
//		AttributePK attrPK = new AttributePK();
//		attrPK.setName(name);
//		this.setId(attrPK);
		
		this.setId(new AttributePK(name));
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

}