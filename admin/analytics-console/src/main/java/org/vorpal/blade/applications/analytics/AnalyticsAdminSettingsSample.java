package org.vorpal.blade.applications.analytics;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/analytics-console.json` on first deployment when
/// no operator-supplied file is present (named from the web.xml display-name
/// `analytics-console` — distinct from the analytics service's config).
public class AnalyticsAdminSettingsSample extends AnalyticsAdminSettings {
	private static final long serialVersionUID = 1L;

	public AnalyticsAdminSettingsSample() {
	}
}
