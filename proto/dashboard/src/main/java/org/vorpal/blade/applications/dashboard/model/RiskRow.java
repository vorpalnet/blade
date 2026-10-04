package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/// One fused risk assessment of a call, as the `v_call_risk` view types it.
///
/// The view does the JSON extraction in each database's own dialect, so the
/// scores arrive here as numbers. A *signal* is a detector's raw reading; a
/// *contribution* is how many log-odds it added to the fused score. See
/// [CallRow] for why these map views.
@Entity
@Table(name = "v_call_risk")
public class RiskRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Column(name = "call_id")
	private String callId;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "assessed_at")
	private Date assessedAt;

	/// `callRiskAssessed` for every scored window, `callRiskFlagged` when a call
	/// crossed the flag threshold.
	@Column(name = "event_type")
	private String eventType;

	private String tenant;

	private String application;

	@Column(name = "cluster_name")
	private String clusterName;

	@Column(name = "vorpal_id")
	private String vorpalId;

	@Column(name = "risk_score")
	private Double riskScore;

	/// `CLEAR`, `WATCH` or `SUSPECT`.
	@Column(name = "risk_band")
	private String riskBand;

	@Column(name = "trigger_signal")
	private String triggerSignal;

	@Column(name = "suspect_streak")
	private Double suspectStreak;

	@Column(name = "signal_acoustic")
	private Double signalAcoustic;

	@Column(name = "signal_signaling")
	private Double signalSignaling;

	@Column(name = "signal_provenance")
	private Double signalProvenance;

	@Column(name = "signal_behavior")
	private Double signalBehavior;

	@Column(name = "contribution_acoustic")
	private Double contributionAcoustic;

	@Column(name = "contribution_signaling")
	private Double contributionSignaling;

	@Column(name = "contribution_provenance")
	private Double contributionProvenance;

	@Column(name = "contribution_behavior")
	private Double contributionBehavior;
}
