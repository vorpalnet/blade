package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Embeddable;

/// Composite primary key for the `attribute` table: `(event_id,
/// attribute_name_id)`. The string name lives on [Attribute] as a wire-only
/// transient field; the consumer translates name → id before persist.
@Embeddable
public class AttributePK implements Serializable {
	private static final long serialVersionUID = 1L;

	@Column(name = "event_id", insertable = true, updatable = false, nullable = false)
	private long eventId;

	@Column(name = "attribute_name_id", insertable = true, updatable = false, nullable = false)
	private short attributeNameId;

	public AttributePK() {
	}

	public AttributePK(long eventId, short attributeNameId) {
		this.eventId = eventId;
		this.attributeNameId = attributeNameId;
	}

	public long getEventId() {
		return this.eventId;
	}

	public void setEventId(long eventId) {
		this.eventId = eventId;
	}

	public short getAttributeNameId() {
		return this.attributeNameId;
	}

	public void setAttributeNameId(short attributeNameId) {
		this.attributeNameId = attributeNameId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof AttributePK)) {
			return false;
		}
		AttributePK castOther = (AttributePK) other;
		return (this.eventId == castOther.eventId) && (this.attributeNameId == castOther.attributeNameId);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int hash = 17;
		hash = hash * prime + ((int) (this.eventId ^ (this.eventId >>> 32)));
		hash = hash * prime + this.attributeNameId;
		return hash;
	}
}
