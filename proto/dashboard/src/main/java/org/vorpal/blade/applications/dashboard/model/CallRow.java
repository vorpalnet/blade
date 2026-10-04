package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/// One call, as the `v_calls` reporting view presents it.
///
/// **Mapped onto the view, never the tables.** The views are the reporting
/// contract: each of the three view scripts (Oracle, MySQL, SQL Server) defines
/// the same columns, so one mapping serves every database BLADE supports, and
/// the tables underneath can change without breaking the dashboard.
///
/// **Read-only by use, not by annotation.** The dashboard only ever selects
/// scalar columns from these entities; nothing persists or merges one.
/// EclipseLink's `@ReadOnly` would say so, but would also make this module
/// compile against EclipseLink rather than the JPA API alone.
@Entity
@Table(name = "v_calls")
public class CallRow implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The session row's key, as text: the view casts the 63-bit id to a string
	/// so a BI tool's double cannot round it.
	@Id
	@Column(name = "call_id")
	private String callId;

	@Column(name = "cluster_name")
	private String clusterName;

	@Column(name = "vorpal_id")
	private String vorpalId;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "started_at")
	private Date startedAt;

	/// Null while the call is up, or forever if the session never closed.
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "ended_at")
	private Date endedAt;

	@Column(name = "duration_seconds")
	private Double durationSeconds;

	@Column(name = "application_id")
	private String applicationId;

	private String application;

	@Column(name = "application_version")
	private String applicationVersion;

	private String host;

	private String domain;

	private String server;

	private String tenant;
}
