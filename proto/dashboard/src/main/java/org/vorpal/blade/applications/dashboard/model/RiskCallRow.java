package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/// The worst a call's risk ever got, one row per call, from
/// `v_call_risk_summary`.
///
/// `peakBandRank` (3 = SUSPECT, 2 = WATCH, 1 = CLEAR) is what sorts correctly;
/// `peakRiskBand` is the word to show beside it. The view groups by call,
/// tenant and cluster, so `callId` can be null for scores no call owned; the
/// dashboard selects columns from this entity and never loads one by id. See
/// [CallRow] for why these map views.
@Entity
@Table(name = "v_call_risk_summary")
public class RiskCallRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "call_id")
	private String callId;

	private String tenant;

	@Column(name = "cluster_name")
	private String clusterName;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "first_assessed_at")
	private Date firstAssessedAt;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "last_assessed_at")
	private Date lastAssessedAt;

	private Long assessments;

	@Column(name = "peak_risk_score")
	private Double peakRiskScore;

	@Column(name = "peak_band_rank")
	private Integer peakBandRank;

	@Column(name = "peak_risk_band")
	private String peakRiskBand;
}
