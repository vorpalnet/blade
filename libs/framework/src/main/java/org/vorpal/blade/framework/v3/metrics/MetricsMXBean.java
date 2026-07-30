package org.vorpal.blade.framework.v3.metrics;

/// Read surface for one app's metrics on one node, registered as
/// `vorpal.blade:Name=<app>,Type=Metrics[,Cluster=<name>]`.
///
/// The `Name=` key matches the app's `Type=Configuration` MBean, so a console
/// that already discovers apps by walking `vorpal.blade:Name=*,Type=Configuration,*`
/// finds their metrics with the key it already has. That was the missing half of
/// `ConfigurationMonitor.queryApps()`: the JMX walk worked, but the only thing
/// behind it was `SettingsMXBean`, which carries configuration and no numbers.
///
/// Payloads are JSON strings rather than `CompositeData`, matching `TesterMXBean`
/// — proven to cross the federated DomainRuntime connection between the admin
/// and engine tiers without the admin side needing the payload classes.
public interface MetricsMXBean {

	/// This node's [MetricsReport] as JSON, or an `{"error": ...}` object.
	String getReportJson();

	/// Number of declared metrics, for a cheap liveness check that does not
	/// serialize the whole report.
	int getMetricCount();

	/// Zero every counter and histogram on this node. Gauges are read-through
	/// and unaffected.
	void reset();
}
