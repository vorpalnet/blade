package org.vorpal.blade.applications.dashboard;

/// Shipped defaults for the Dashboard: 30 days of history, the standard
/// analytics datasource JNDI name.
public class DashboardSettingsSample extends DashboardSettings {

	private static final long serialVersionUID = 1L;

	public DashboardSettingsSample() {
		this.setHistoryDays(30);
		this.setAnalyticsDataSource("jdbc/BladeAnalytics");
	}
}
