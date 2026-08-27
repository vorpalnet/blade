package org.vorpal.blade.services.analytics.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import org.vorpal.blade.framework.v3.analytics.NaturalKey;

/// One recorded fact about a call.
///
/// **The key is [#idFor] of the CloudEvent id**, which makes redelivery a
/// non-event: a durable subscription replays on every rolling restart, and a
/// replayed event computes the key it already has and collides with its own
/// row instead of becoming a second one. There is no separate dedup column to
/// keep in step with the key, because the key *is* the dedup.
///
/// **The type name and the attributes live here, not in satellite tables.**
/// They were normalized into `event_types` and an `attributes`/`attribute_names`
/// pair, which bought two bytes per attribute and cost: an interning cache in
/// front of every insert, a race between cluster members creating the same
/// lookup row, one insert per attribute, and a reader who could not ask
/// "which calls scored above 0.8" without a join and a string-to-number cast.
/// The name is a column again — as it was originally — and the attributes are
/// one JSON document the database can index by path.
@Entity
@Table(name = "events")
@NamedQuery(name = "Event.findAll", query = "SELECT e FROM Event e")
@JsonPropertyOrder({ "type", "payload", "id", "applicationId", "sessionId", "created", "eventUid" })
public class Event implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(updatable = false, unique = true, nullable = false)
	private long id;

	@Column(name = "application_id", nullable = false)
	private long applicationId;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable = false, nullable = false)
	private Date created;

	/// The event name — `callStarted`, `callRiskAssessed`. The framework's own
	/// names arrive as the CloudEvents type and an operator's arrives in the
	/// payload under a generic type; either way the short name lands here.
	@Column(nullable = false, length = 64)
	private String type;

	@Column(name = "session_id")
	private Long sessionId;

	/// The CloudEvent `id` this row was built from.
	///
	/// Kept although [#id] is derived from it, because the derivation is one
	/// way: an operator holding an id out of a producer's log needs to find the
	/// row, and no one can invert a hash. Unique, so a genuine hash collision
	/// between two different CloudEvent ids fails the insert instead of
	/// silently overwriting the first event with the second.
	@Column(name = "event_uid", updatable = false, nullable = false, length = 36)
	private String eventUid;

	/// The event's attributes, as one JSON object of name to value.
	///
	/// Mapped as a large string rather than a vendor JSON type so one mapping
	/// serves both databases; the *column* is native JSON on MySQL and a CLOB
	/// with an `IS JSON` check on Oracle, and both index extracted paths, which
	/// is what the reader actually needs.
	@Lob
	@Column(nullable = true)
	private String payload;

	public Event() {
		this.created = new Date();
	}

	/// This event's key, from the CloudEvent id that identifies it.
	public static long idFor(String eventUid) {
		return NaturalKey.idFor(eventUid);
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

	public String getType() {
		return this.type;
	}

	public void setType(String type) {
		this.type = type;
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

	/// Sets the CloudEvent id **and** the primary key derived from it, so the
	/// two cannot be set inconsistently by a caller who sets only one.
	public void setEventUid(String eventUid) {
		this.eventUid = eventUid;
		this.id = idFor(eventUid);
	}

	public String getPayload() {
		return this.payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}
}
