package org.vorpal.blade.applications.dashboard;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Dashboard.
///
/// `@SchemaAbout` puts the launcher card on the Admin Portal deck; the portal
/// reads `title` / `x-tagline` / `description` from this class's generated
/// schema.
@SchemaAbout(
		name = "Dashboard",
		tagline = "Live cluster health and call analytics",
		description = "One pane of glass for OCCAS and BLADE: node state, sessions and throughput read from the runtime MBeans, and call trends drawn from the analytics reporting views. The operational view the Remote Console never gave you.")
public class DashboardSettings extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	private int historyDays = 30;
	private String analyticsDataSource = "jdbc/BladeAnalytics";

	@JsonPropertyDescription("How many days of call history the trend charts cover by default. The browser can ask for a shorter window; this is the ceiling the charts open with.")
	public int getHistoryDays() {
		return historyDays;
	}

	public void setHistoryDays(int historyDays) {
		this.historyDays = (historyDays < 1) ? 1 : historyDays;
	}

	@JsonPropertyDescription("JNDI name of the analytics datasource the charts query. Must be targeted at the AdminServer as well as the engine tier so this admin app can look it up.")
	public String getAnalyticsDataSource() {
		return analyticsDataSource;
	}

	public void setAnalyticsDataSource(String analyticsDataSource) {
		this.analyticsDataSource = (analyticsDataSource == null || analyticsDataSource.isEmpty())
				? "jdbc/BladeAnalytics" : analyticsDataSource;
	}
}
