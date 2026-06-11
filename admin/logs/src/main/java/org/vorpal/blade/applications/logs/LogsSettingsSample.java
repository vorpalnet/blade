package org.vorpal.blade.applications.logs;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/logs.json` on first deployment when no
/// operator-supplied file is present.
public class LogsSettingsSample extends LogsSettings {
	private static final long serialVersionUID = 1L;

	public LogsSettingsSample() {
	}
}
