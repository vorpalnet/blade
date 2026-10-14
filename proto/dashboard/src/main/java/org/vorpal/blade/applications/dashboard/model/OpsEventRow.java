package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// One operations event, from `v_ops_events`: an endpoint, remote service or
/// trunk going down or coming back, a problem an application raised or
/// cleared, or a minute a queue held callers. Published on the change, so
/// these are few. Like [ChangeRow] it carries the publishing server's domain,
/// not a `cluster_name`. See [CallRow] for why these map views.
@Entity
@Table(name = "v_ops_events")
public class OpsEventRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "occurred_at")
	private Date occurredAt;

	/// The short name: `endpointDown`, `trunkFailed`, `queueDepth`, ...
	@Column(name = "event_type")
	private String eventType;

	/// The server that saw it.
	private String node;

	private String application;

	/// What it is about: the endpoint, remote service, gateway, queue or
	/// problem key.
	private String subject;

	/// What was observed: a note, a reason, a problem's summary.
	private String detail;

	@Column(name = "remote_kind")
	private String remoteKind;

	private String registrar;

	@Column(name = "status_code")
	private Long statusCode;

	@Column(name = "low_waiting")
	private Long lowWaiting;

	@Column(name = "high_waiting")
	private Long highWaiting;

	@Column(name = "waiting_now")
	private Long waitingNow;

	/// The admin page that fixes a raised problem.
	@Column(name = "open_page")
	private String openPage;

	private String domain;
}
