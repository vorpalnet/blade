package org.vorpal.blade.applications.metrics;

/// Shipped defaults for the Metrics console.
public class MetricsAdminSettingsSample extends MetricsAdminSettings {
	private static final long serialVersionUID = 1L;

	public MetricsAdminSettingsSample() {
		this.setRefreshSeconds(10);
	}
}
