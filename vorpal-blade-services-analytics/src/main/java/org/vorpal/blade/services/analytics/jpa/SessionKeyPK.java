package org.vorpal.blade.services.analytics.jpa;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Embeddable;

/**
 * The primary key class for the session_key database table.
 * 
 */
@Embeddable
public class SessionKeyPK implements Serializable {
	//default serial version id, required for serializable classes.
	private static final long serialVersionUID = 1L;

	@Column(name="session_id", insertable=true, updatable=false, unique=true, nullable=false)
	private long sessionId;

	@Column(unique=true, nullable=false, length=64)
	private String name;

	@Column(unique=true, nullable=false, length=256)
	private String value;

	public SessionKeyPK() {
	}
	public long getSessionId() {
		return this.sessionId;
	}
	public void setSessionId(long sessionId) {
		this.sessionId = sessionId;
	}
	public String getName() {
		return this.name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getValue() {
		return this.value;
	}
	public void setValue(String value) {
		this.value = value;
	}

	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof SessionKeyPK)) {
			return false;
		}
		SessionKeyPK castOther = (SessionKeyPK)other;
		return 
			(this.sessionId == castOther.sessionId)
			&& this.name.equals(castOther.name)
			&& this.value.equals(castOther.value);
	}

	public int hashCode() {
		final int prime = 31;
		int hash = 17;
		hash = hash * prime + ((int) (this.sessionId ^ (this.sessionId >>> 32)));
		hash = hash * prime + this.name.hashCode();
		hash = hash * prime + this.value.hashCode();
		
		return hash;
	}
}