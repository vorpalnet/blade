package org.vorpal.blade.services.analytics.model;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/// One call, as it flows through every application in a chain.
///
/// The key is [#idFor] of the correlator the producer already sends — the
/// cluster, the X-Vorpal-ID and the call's birth instant. Any node handling any
/// part of the call computes the same id without asking the database, so
/// nothing depends on a session start arriving before the events that reference
/// it: whoever gets there first writes the row, and the others collide with it.
///
/// **The birth instant is part of the identity, not decoration.** The
/// X-Vorpal-ID is 32 bits and is reused over time, so `(cluster, vorpal_id)`
/// alone eventually names two different calls. `created` carries millisecond
/// precision throughout the schema for this reason.
@Entity
@Table(name = "sessions")
@NamedQueries({
		@NamedQuery(name = "Session.findAll", query = "SELECT s FROM Session s"),
		@NamedQuery(name = "Session.findOpen",
				query = "SELECT s FROM Session s WHERE s.clusterName = :clusterName"
						+ " AND s.vorpalId = :vorpalId AND s.destroyed IS NULL") })
public class Session implements Serializable {
	private static final long serialVersionUID = 1L;
	private long id;
	private long applicationId;
	private long vorpalId;
	private String clusterName;
	private Date created;
	private Timestamp destroyed;

	public Session() {
	}

	/// This session's key, from the correlator on the wire. Callers use this
	/// instead of inserting a row to find out what its id turned out to be.
	public static long idFor(String clusterName, long vorpalId, Date created) {
		return NaturalKey.idFor(clusterName, vorpalId, created);
	}

	@Id
	@Column(unique = true, nullable = false)
	public long getId() {
		return this.id;
	}

	public void setId(long id) {
		this.id = id;
	}

	@Column(name = "application_id", nullable = false)
	public long getApplicationId() {
		return this.applicationId;
	}

	public void setApplicationId(long applicationId) {
		this.applicationId = applicationId;
	}

	/** Cluster-unique tracking id for the call (the X-Vorpal-ID); the correlator
	 *  the consumer maps to {@link #id}. */
	@Column(name = "vorpal_id", nullable = false)
	public long getVorpalId() {
		return this.vorpalId;
	}

	public void setVorpalId(long vorpalId) {
		this.vorpalId = vorpalId;
	}

	@Column(name = "cluster_name", nullable = false, length = 64)
	public String getClusterName() {
		return this.clusterName;
	}

	public void setClusterName(String clusterName) {
		this.clusterName = clusterName;
	}

	@Temporal(TemporalType.TIMESTAMP)
	@Column(nullable = false)
	public Date getCreated() {
		return this.created;
	}

	public void setCreated(Date created) {
		this.created = created;
	}

	public Timestamp getDestroyed() {
		return this.destroyed;
	}

	public void setDestroyed(Timestamp destroyed) {
		this.destroyed = destroyed;
	}

}
