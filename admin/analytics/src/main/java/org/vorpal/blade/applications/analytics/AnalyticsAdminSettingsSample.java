package org.vorpal.blade.applications.analytics;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/analytics-admin.json` on first deployment when
/// no operator-supplied file is present.
public class AnalyticsAdminSettingsSample extends AnalyticsAdminSettings {
	private static final long serialVersionUID = 1L;

	public AnalyticsAdminSettingsSample() {
		this.about.setName("Analytics")
				.setTagline("Pipeline Status & Config")
				.setDescription("Audit the WebLogic resources the analytics pipeline depends on — JMS Server, Connection Factory, Distributed Queue, JDBC Data Source — and (in upcoming releases) create what's missing and visualize what's flowing through.");
	}
}
