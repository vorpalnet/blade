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
	private int heapHighPercent = 85;
	private int leakHours = 24;
	private int splitCallSeconds = 60;
	private int sampleFreshMinutes = 3;
	private int liveRefreshSeconds = 5;
	private int analyticsRefreshSeconds = 60;
	private int cacheMinutes = 5;
	private java.util.List<DashboardGrant> grants = new java.util.ArrayList<>();

	@JsonPropertyDescription("Days of history a data request covers when it names none. The board and the report pages always name their own range.")
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

	@JsonPropertyDescription("Heap use, in percent, at which a server's heap reads \"high\" on the board.")
	public int getHeapHighPercent() {
		return heapHighPercent;
	}

	public void setHeapHighPercent(int heapHighPercent) {
		this.heapHighPercent = clamp(heapHighPercent, 1, 100);
	}

	@JsonPropertyDescription("Hours a session may stay open before it counts as leaked rather than as a call in progress.")
	public int getLeakHours() {
		return leakHours;
	}

	public void setLeakHours(int leakHours) {
		this.leakHours = clamp(leakHours, 1, 24 * 30);
	}

	@JsonPropertyDescription("Seconds within which two calls with one Vorpal-ID are one call split in two, not the ID reused.")
	public int getSplitCallSeconds() {
		return splitCallSeconds;
	}

	public void setSplitCallSeconds(int splitCallSeconds) {
		this.splitCallSeconds = clamp(splitCallSeconds, 1, 3600);
	}

	@JsonPropertyDescription("Minutes a server's last sample stands for it. Past that, a domain read from its samples shows the server as gone quiet.")
	public int getSampleFreshMinutes() {
		return sampleFreshMinutes;
	}

	public void setSampleFreshMinutes(int sampleFreshMinutes) {
		this.sampleFreshMinutes = clamp(sampleFreshMinutes, 1, 60);
	}

	@JsonPropertyDescription("Seconds between the board's reads of this domain's servers and operations.")
	public int getLiveRefreshSeconds() {
		return liveRefreshSeconds;
	}

	public void setLiveRefreshSeconds(int liveRefreshSeconds) {
		this.liveRefreshSeconds = clamp(liveRefreshSeconds, 2, 300);
	}

	@JsonPropertyDescription("Seconds between the board's reads of the call analytics.")
	public int getAnalyticsRefreshSeconds() {
		return analyticsRefreshSeconds;
	}

	public void setAnalyticsRefreshSeconds(int analyticsRefreshSeconds) {
		this.analyticsRefreshSeconds = clamp(analyticsRefreshSeconds, 10, 3600);
	}

	@JsonPropertyDescription("Minutes a report's result is reused before the database is asked again. Every open board shares it.")
	public int getCacheMinutes() {
		return cacheMinutes;
	}

	public void setCacheMinutes(int cacheMinutes) {
		this.cacheMinutes = clamp(cacheMinutes, 0, 60);
	}

	@JsonPropertyDescription("Who sees what: one entry per group. A viewer sees the union of their groups' grants, and a viewer in none sees nothing. With no grants at all, every signed-in viewer sees everything.")
	public java.util.List<DashboardGrant> getGrants() {
		return grants;
	}

	public void setGrants(java.util.List<DashboardGrant> grants) {
		this.grants = grants;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
