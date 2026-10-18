package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// What the flight recorder found on one call that went wrong, from
/// `v_call_traced`: one row per application that recorded the call. The
/// finding names are the framework's (`CallLadder.Verdict`). See [CallRow] for
/// why these map views.
@Entity
@Table(name = "v_call_traced")
public class TracedRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Column(name = "call_id")
	private String callId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "traced_at")
	private Date tracedAt;

	private String application;

	private String server;

	private String domain;

	private String tenant;

	private String department;

	@Column(name = "cluster_name")
	private String clusterName;

	@Column(name = "vorpal_id")
	private String vorpalId;

	/// `downstream` or `application`; null for a call dropped with no finding.
	private String fault;

	private String finding;

	private String method;

	@Column(name = "status_code")
	private Long statusCode;

	/// The server the finding names, host and port.
	private String peer;

	/// `true` when the call was dropped.
	private String dropped;
}
