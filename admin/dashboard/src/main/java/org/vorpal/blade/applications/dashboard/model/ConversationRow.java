package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// What a caller said and what the system made of it, from `v_conversation`.
///
/// The view also carries `callStarted` rows (for the caller and destination of
/// each call), so every utterance count filters on `eventType = 'callerSaid'`.
/// See [CallRow] for why these map views.
@Entity
@Table(name = "v_conversation")
public class ConversationRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Column(name = "call_id")
	private String callId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "occurred_at")
	private Date occurredAt;

	@Column(name = "event_type")
	private String eventType;

	private String tenant;

	/// The department that handled this part of the call, or null.
	private String department;

	private String application;

	@Column(name = "cluster_name")
	private String clusterName;

	@Column(name = "vorpal_id")
	private String vorpalId;

	private String said;

	private String intent;

	private String entity;

	private String addressed;

	private String caller;

	private String destination;
}
