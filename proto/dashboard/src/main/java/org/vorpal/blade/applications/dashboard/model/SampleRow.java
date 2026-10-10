package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// What one application on one node did over one sample, from
/// `v_app_samples`: the dashboard's per-minute reading of its metrics MBean.
///
/// `weblogicCluster` is the WebLogic cluster the node belongs to, not the
/// analytics `cluster_name` stamp every other view carries, which is why the
/// view names it differently. See [CallRow] for why these map views.
@Entity
@Table(name = "v_app_samples")
public class SampleRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "sampled_at")
	private Date sampledAt;

	private String application;

	private String node;

	@Column(name = "weblogic_cluster")
	private String weblogicCluster;

	/// The WebLogic domain of the server that published the sample.
	private String domain;

	private Long seconds;

	private Long published;

	/// Publishes the bus refused: each one an event analytics never received.
	private Long failures;

	private Long invites;

	@Column(name = "sip_errors")
	private Long sipErrors;

	/// `true` when the application restarted within the sample.
	private String restarted;

	/// Application sessions the container held for it at the sample; null for
	/// an application that runs no SIP.
	@Column(name = "active_app_sessions")
	private Long activeAppSessions;
}
