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

	private int defaultDays = 7;
	private java.util.List<Integer> ranges = new java.util.ArrayList<>(java.util.Arrays.asList(1, 7, 30));
	private String analyticsDataSource = "jdbc/BladeAnalytics";
	private int heapHighPercent = 85;
	private int healthyPercent = 90;
	private int unhealthyPercent = 60;
	private int leakHours = 24;
	private int splitCallSeconds = 60;
	private int sampleFreshMinutes = 3;
	private int liveRefreshSeconds = 5;
	private int analyticsRefreshSeconds = 60;
	private int cacheMinutes = 5;
	private java.util.List<DashboardGrant> grants = new java.util.ArrayList<>();
	private java.util.List<NetworkSite> sites = new java.util.ArrayList<>();
	private int networkWindowMinutes = 15;
	private int networkFailingCalls = 3;

	/// The longest range a request may ask for, in days.
	static final int MAX_RANGE_DAYS = 400;

	@JsonPropertyDescription("The range, in days, a viewer starts on: the board's first range button, the report pages' first choice, and what a data request covers when it names none. A viewer's own last choice, kept in their browser, overrides it while it is still one of the ranges. Default 7.")
	public int getDefaultDays() {
		return defaultDays;
	}

	public void setDefaultDays(int defaultDays) {
		this.defaultDays = Math.max(1, Math.min(MAX_RANGE_DAYS, defaultDays));
	}

	@JsonPropertyDescription("The ranges a viewer can choose, in days, shortest first: one button each on the board, labelled \"Last day\" for 1 and \"N days\" otherwise. The default range is always among them. Default [1, 7, 30].")
	public java.util.List<Integer> getRanges() {
		java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
		if (ranges != null) {
			for (Integer d : ranges) {
				if (d != null && d >= 1 && d <= MAX_RANGE_DAYS) {
					set.add(d);
				}
			}
		}
		set.add(defaultDays);
		return new java.util.ArrayList<>(set);
	}

	public void setRanges(java.util.List<Integer> ranges) {
		this.ranges = ranges;
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

	@JsonPropertyDescription("Share of calls, in percent, that must end completed for a place on the map to read healthy. Between this and unhealthyPercent the colour runs from healthy to unhealthy.")
	public int getHealthyPercent() {
		return healthyPercent;
	}

	public void setHealthyPercent(int healthyPercent) {
		this.healthyPercent = clamp(healthyPercent, 1, 100);
	}

	@JsonPropertyDescription("Share of calls, in percent, ending completed at or below which a place on the map reads unhealthy.")
	public int getUnhealthyPercent() {
		return unhealthyPercent;
	}

	public void setUnhealthyPercent(int unhealthyPercent) {
		this.unhealthyPercent = clamp(unhealthyPercent, 0, 99);
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

	@JsonPropertyDescription("The places on the network map: where this domain's servers run, where calls arrive from, and where they are sent. A balancer site with coordinates is added for it.")
	public java.util.List<NetworkSite> getSites() {
		return sites;
	}

	public void setSites(java.util.List<NetworkSite> sites) {
		this.sites = sites == null ? new java.util.ArrayList<>() : sites;
	}

	@JsonPropertyDescription("Minutes of refused calls the network map counts against a downstream site.")
	public int getNetworkWindowMinutes() {
		return networkWindowMinutes;
	}

	public void setNetworkWindowMinutes(int networkWindowMinutes) {
		this.networkWindowMinutes = clamp(networkWindowMinutes, 1, 24 * 60);
	}

	@JsonPropertyDescription("Calls a site's servers failed (408, 5xx) within networkWindowMinutes at which the site reads down rather than in trouble.")
	public int getNetworkFailingCalls() {
		return networkFailingCalls;
	}

	public void setNetworkFailingCalls(int networkFailingCalls) {
		this.networkFailingCalls = clamp(networkFailingCalls, 1, 100000);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
