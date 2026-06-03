package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/**
 * The persistent class for the session database table.
 *
 * The database assigns {@code id} (AUTO_INCREMENT). The producer does not know
 * that key — it sends the cluster-unique {@code vorpalId} (the X-Vorpal-ID for
 * the call), and the consumer ({@code AnalyticsJmsListener}) maps
 * vorpalId&nbsp;-&gt;&nbsp;id on the session-started message, then resolves every
 * later event and session key for the call through that map.
 */
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

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
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
