package org.vorpal.blade.framework.v3.metrics;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One node's complete metrics snapshot for one deployed app — the payload
/// behind [MetricsMXBean#getReportJson()].
///
/// [#getApp] matches the `Name=` key of the app's `Type=Configuration` MBean, so
/// the console can join metrics to configuration without a second lookup.
/// [#getNode] is the WebLogic server name; every node reports independently and
/// the admin tier aggregates, because nothing here is a clustered singleton.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "app", "node", "cluster", "timestamp", "uptimeMillis", "counters", "gauges", "histograms" })
public class MetricsReport implements Serializable {

	private static final long serialVersionUID = 1L;

	private String app;
	private String node;
	private String cluster;
	private String timestamp;
	private long uptimeMillis;
	private List<CounterReport> counters;
	private List<GaugeReport> gauges;
	private List<HistogramReport> histograms;

	/// The app's flattened context name — the same value as the `Name=` key on
	/// its Configuration MBean.
	public String getApp() {
		return app;
	}

	public void setApp(String app) {
		this.app = app;
	}

	/// The WebLogic server this snapshot came from.
	public String getNode() {
		return node;
	}

	public void setNode(String node) {
		this.node = node;
	}

	/// The cluster this node belongs to, or null on a standalone server.
	public String getCluster() {
		return cluster;
	}

	public void setCluster(String cluster) {
		this.cluster = cluster;
	}

	/// When the snapshot was taken, ISO-8601 UTC.
	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}

	/// Milliseconds since the registry was created, i.e. since this app started
	/// on this node. Rates are only meaningful against this — a counter total
	/// says nothing without the window it accumulated over.
	public long getUptimeMillis() {
		return uptimeMillis;
	}

	public void setUptimeMillis(long uptimeMillis) {
		this.uptimeMillis = uptimeMillis;
	}

	public List<CounterReport> getCounters() {
		return counters;
	}

	public void setCounters(List<CounterReport> counters) {
		this.counters = counters;
	}

	public List<GaugeReport> getGauges() {
		return gauges;
	}

	public void setGauges(List<GaugeReport> gauges) {
		this.gauges = gauges;
	}

	public List<HistogramReport> getHistograms() {
		return histograms;
	}

	public void setHistograms(List<HistogramReport> histograms) {
		this.histograms = histograms;
	}
}
