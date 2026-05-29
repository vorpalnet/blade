package org.vorpal.blade.applications.logs;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/logs.json` on first deployment when no
/// operator-supplied file is present.
public class LogsSettingsSample extends LogsSettings {
	private static final long serialVersionUID = 1L;

	public LogsSettingsSample() {
		this.about.setName("Logs")
				.setTagline("Cluster Log Tail")
				.setDescription("Stream and filter logs from every node in the cluster. Merges per-server output by timestamp; supports phase filters, severity filters, and live follow mode.");
	}
}
