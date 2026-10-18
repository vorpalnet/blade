package org.vorpal.blade.applications.dashboard;

import java.util.Arrays;

/// Shipped defaults for the Dashboard: a week of history, the standard
/// analytics datasource JNDI name.
public class DashboardSettingsSample extends DashboardSettings {

	private static final long serialVersionUID = 1L;

	public DashboardSettingsSample() {
		this.setDefaultDays(7);
		this.setRanges(java.util.Arrays.asList(1, 7, 30));
		this.setAnalyticsDataSource("jdbc/BladeAnalytics");

		// The network map: where this domain runs, a carrier that both delivers
		// and takes calls, and a PBX calls are sent to. Placeholder names and
		// documentation addresses: replace them with the real sites.
		NetworkSite dc = new NetworkSite("dc-east", "Ashburn, VA", 39.04, -77.49, Arrays.asList("cluster"));
		dc.setServers(Arrays.asList("AdminServer", "engine1", "engine2"));
		NetworkSite carrier = new NetworkSite("carrier-west", "Hillsboro, OR", 45.54, -122.94,
				Arrays.asList("inbound", "downstream"));
		carrier.setAddresses(Arrays.asList("sip.carrier.example.net", "192.0.2.0/28"));
		carrier.setQuietMinutes(30);
		NetworkSite pbx = new NetworkSite("pbx-central", "Dallas, TX", 32.78, -96.80, Arrays.asList("downstream"));
		pbx.setAddresses(Arrays.asList("pbx.example.com", "198.51.100.20"));
		this.setSites(new java.util.ArrayList<>(Arrays.asList(dc, carrier, pbx)));
	}
}
