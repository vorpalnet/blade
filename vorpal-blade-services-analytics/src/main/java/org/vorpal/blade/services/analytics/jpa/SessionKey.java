package org.vorpal.blade.services.analytics.jpa;

import java.io.Serializable;

import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.NamedQuery;
import javax.persistence.Table;


/**
 * The persistent class for the session_key database table.
 * 
 */
@Entity
@Table(name="session_key")
@NamedQuery(name="SessionKey.findAll", query="SELECT s FROM SessionKey s")
public class SessionKey implements Serializable {
	private static final long serialVersionUID = 1L;

	@EmbeddedId
	private SessionKeyPK id;

	public SessionKey() {
	}

	public SessionKeyPK getId() {
		return this.id;
	}

	public void setId(SessionKeyPK id) {
		this.id = id;
	}

}