package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// How one server stood over one sample, from `v_server_samples`. Counts
/// (`received`, `rejected`, `appSessionsCreated`) cover the sample; the rest
/// are readings taken at it, and are null where the server has no such thing.
/// See [CallRow] for why these map views, and [SampleRow] for why the cluster
/// column is named apart.
@Entity
@Table(name = "v_server_samples")
public class ServerSampleRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "sampled_at")
	private Date sampledAt;

	private String node;

	@Column(name = "weblogic_cluster")
	private String weblogicCluster;

	/// The WebLogic domain of the server that published the sample.
	private String domain;

	private String state;

	private String restarted;

	private Long seconds;

	private Long received;

	/// Messages the SIP container refused: calls no application ever saw.
	private Long rejected;

	@Column(name = "app_sessions_created")
	private Long appSessionsCreated;

	/// Initial requests the App Router routed; null where it does not run.
	private Long routed;

	/// Requests no routing rule matched: the default application took them.
	@Column(name = "routing_fallbacks")
	private Long routingFallbacks;

	/// Requests routed past an application that was not deployed.
	@Column(name = "undeployed_bypasses")
	private Long undeployedBypasses;

	@Column(name = "routing_cycles")
	private Long routingCycles;

	@Column(name = "active_app_sessions")
	private Long activeAppSessions;

	@Column(name = "active_sip_sessions")
	private Long activeSipSessions;

	@Column(name = "heap_used_pct")
	private Long heapUsedPct;

	/// CPU the server's JVM used over the minute, percent of all its processors.
	@Column(name = "cpu_pct")
	private Long cpuPct;

	@Column(name = "threads_stuck")
	private Long threadsStuck;

	@Column(name = "threads_hogging")
	private Long threadsHogging;

	@Column(name = "threads_pending")
	private Long threadsPending;

	@Column(name = "metaspace_mb")
	private Long metaspaceMb;

	@Column(name = "classes_loaded")
	private Long classesLoaded;
}
