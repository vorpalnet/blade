package org.vorpal.blade.applications.console.tuning;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/tuning.json` on first deployment when no
/// operator-supplied file is present.
public class TuningSettingsSample extends TuningSettings {
	private static final long serialVersionUID = 1L;

	public TuningSettingsSample() {
		this.jvmProfiles = new java.util.ArrayList<>(java.util.Arrays.asList(
				new JvmProfile("engine", "-server -Xms2g -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError"),
				new JvmProfile("admin", "-server -Xms512m -Xmx1g -XX:+UseG1GC")));
	}
}
