package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// Where a call was sent, and what came back, as the `v_call_routing` view
/// presents it: a proxy's choice (`callRouted`), an answer a router gave
/// itself (`callResponded`), how the call ended refused (`callDeclined`), and
/// each leg a server refused (`branchFailed`), with the status, the
/// Request-URI it was sent to and the address that answered. The network map
/// counts a downstream server's refusals from `branchFailed`. See [CallRow] for why these map views.
@Entity
@Table(name = "v_call_routing")
public class RoutingRow implements Serializable {
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

	private String department;

	private String application;

	@Column(name = "cluster_name")
	private String clusterName;

	/// The Request-URI the call was sent to.
	private String destination;

	/// The balancer endpoint chosen, for a balancer's `callRouted`.
	private String endpoint;

	/// The status code of a refusal or of a router's own answer.
	private Long status;

	/// A `branchFailed`'s reason phrase, as the server wrote it.
	private String reason;

	/// A `branchFailed`'s responding address; none when the container answered
	/// for a server that never did.
	private String responder;

	/// Seconds from a refusal's Retry-After.
	@Column(name = "retry_after")
	private Long retryAfter;
}
