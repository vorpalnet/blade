package org.vorpal.blade.services.analytics.sip;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Analytics",
		tagline = "Per-Call Records & Metrics",
		description = "Captures per-call records and metrics from SIP traffic as it flows through "
				+ "the cluster — call counts, status distributions, and timing — for reporting "
				+ "and troubleshooting.")
public class AnalyticsConfig extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Interval in seconds between database health check queries")
	@JsonProperty(defaultValue = "30")
	public Integer healthCheckInterval;

	@JsonPropertyDescription("SQL query used to verify database connectivity")
	@JsonProperty(defaultValue = "SELECT 1 FROM DUAL")
	public String healthCheckSql;

	@JsonPropertyDescription("Stable id for this hosting environment (e.g. \"SIPREC-03\"), stamped on every analytics row this server writes as cluster_name. Differentiates domains that share a WebLogic domain name and feed one analytics database; defaults to the WebLogic domain name if unset.")
	public String domainId;

}
