package org.vorpal.blade.framework.v3.tester;

import javax.management.MXBean;

/// Admin-tier control surface for a tester deployment, registered per node
/// as `vorpal.blade:Name=<app>,Type=TesterControl[,Cluster=<name>]`. The
/// BLADE Test Console reaches every node's instance over federated JMX
/// (DomainRuntime) — the admin tier never uses REST internally.
///
/// All payloads are JSON strings: [LoadRequest] in, [LoadStatus] /
/// [ScenarioReport] list out. MXBean rules expose the no-arg getters as
/// attributes (`StatusJson`, `ReportJson`) and the rest as operations.
@MXBean
public interface TesterMXBean {

	/// Current [LoadStatus] as JSON.
	String getStatusJson();

	/// Per-scenario [ScenarioReport] list as JSON.
	String getReportJson();

	/// Starts a load run from a [LoadRequest] JSON payload (null or empty
	/// uses the configured defaults). Returns the resulting status JSON, or
	/// an `{"error": ...}` object.
	String startLoad(String loadRequestJson);

	/// Stops the running load; active calls drain. Returns status JSON.
	String stopLoad();

	/// Clears all per-scenario metrics.
	void resetMetrics();
}
