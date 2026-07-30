package org.vorpal.blade.applications.metrics;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Metrics console.
///
/// `@SchemaAbout` is what puts the card on the Admin Portal deck.
@SchemaAbout(
		name = "Metrics",
		tagline = "Counters, gauges and latency for every app",
		description = "What every BLADE application on every node is actually doing — calls, outcomes, handler latency and whatever counters an app declares — aggregated across the cluster. Counters and gauges sum; latency merges by bucket, so the percentile shown is the cluster's and not the worst node's.")
public class MetricsAdminSettings extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	private int refreshSeconds = 10;

	@JsonPropertyDescription("How often the page re-reads the metrics MBeans. Each refresh is one JMX read per app per node, so a short interval on a large cluster is real work on the AdminServer.")
	public int getRefreshSeconds() {
		return refreshSeconds;
	}

	public void setRefreshSeconds(int refreshSeconds) {
		this.refreshSeconds = (refreshSeconds < 2) ? 2 : refreshSeconds;
	}
}
