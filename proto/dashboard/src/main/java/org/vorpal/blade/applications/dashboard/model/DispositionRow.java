package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// An agent's verdict on one call, from `v_call_disposition`. Joined to
/// [RiskCallRow] by `callId`, it is the ground truth the risk score is judged
/// against. See [CallRow] for why these map views.
@Entity
@Table(name = "v_call_disposition")
public class DispositionRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Column(name = "call_id")
	private String callId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "dispositioned_at")
	private Date dispositionedAt;

	private String tenant;

	/// The department that handled this part of the call, or null.
	private String department;

	private String application;

	@Column(name = "cluster_name")
	private String clusterName;

	@Column(name = "vorpal_id")
	private String vorpalId;

	/// The agent's verdict, e.g. legitimate or scam.
	private String outcome;

	private String treatment;

	/// `true` when the agent blocked the caller's number.
	private String blocked;

	private String agent;

	/// Who the agent judged the caller to be. Named apart in the view:
	/// `identity` is reserved in SQL Server.
	@Column(name = "caller_identity")
	private String identity;

	private String action;

	private String reasons;

	/// The verdict face the agent chose on the card, 1 to 7.
	private Integer face;
}
