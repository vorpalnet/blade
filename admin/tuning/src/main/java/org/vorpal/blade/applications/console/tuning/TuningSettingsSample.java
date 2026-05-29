package org.vorpal.blade.applications.console.tuning;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/tuning.json` on first deployment when no
/// operator-supplied file is present.
public class TuningSettingsSample extends TuningSettings {
	private static final long serialVersionUID = 1L;

	public TuningSettingsSample() {
		this.about.setName("Tuning")
				.setTagline("OCCAS Performance Dashboard")
				.setDescription("Edit JVM heap and GC, SIP protocol timers, WebLogic work-manager constraints, server thread pools, cluster topology, Node Manager, and SSO settings via JMX. Live read / write against the running domain.");
	}
}
