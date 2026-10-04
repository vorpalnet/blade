package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/// One recorded fact, as the `v_events` reporting view presents it.
///
/// The view's `payload` column is deliberately not mapped: it is a JSON
/// document whose type differs per database (CLOB, JSON, NVARCHAR(MAX)), and
/// every typed field the dashboard needs is already flattened by a dedicated
/// view ([RiskRow], [ConversationRow]). See [CallRow] for why these map views.
@Entity
@Table(name = "v_events")
public class EventRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	/// The short event name: `callStarted`, `callAnswered`, `start`, `stop`.
	@Column(name = "event_type")
	private String eventType;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "occurred_at")
	private Date occurredAt;

	@Column(name = "event_uid")
	private String eventUid;

	/// Null for an event no call owns, such as an application's start.
	@Column(name = "call_id")
	private String callId;

	@Column(name = "application_id")
	private String applicationId;

	private String application;

	private String tenant;

	@Column(name = "cluster_name")
	private String clusterName;

	@Column(name = "vorpal_id")
	private String vorpalId;
}
