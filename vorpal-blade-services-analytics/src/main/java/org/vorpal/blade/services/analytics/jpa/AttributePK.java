package org.vorpal.blade.services.analytics.jpa;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Embeddable;

/**
 * The primary key class for the attribute database table.
 * 
 */
@Embeddable
public class AttributePK implements Serializable {
	//default serial version id, required for serializable classes.
	private static final long serialVersionUID = 1L;

	@Column(name="event_id", insertable=true, updatable=false, unique=true, nullable=false)
	private long eventId;

	@Column(unique=true, nullable=false, length=64)
	private String name;

	public AttributePK() {
	}
	public long getEventId() {
		return this.eventId;
	}
	public void setEventId(long eventId) {
		this.eventId = eventId;
	}
	public String getName() {
		return this.name;
	}
	public void setName(String name) {
		this.name = name;
	}

	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof AttributePK)) {
			return false;
		}
		AttributePK castOther = (AttributePK)other;
		return 
			(this.eventId == castOther.eventId)
			&& this.name.equals(castOther.name);
	}

	public int hashCode() {
		final int prime = 31;
		int hash = 17;
		hash = hash * prime + ((int) (this.eventId ^ (this.eventId >>> 32)));
		hash = hash * prime + this.name.hashCode();
		
		return hash;
	}
}