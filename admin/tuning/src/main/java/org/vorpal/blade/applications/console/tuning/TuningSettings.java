package org.vorpal.blade.applications.console.tuning;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Tuning admin app. Most per-knob settings live in WLS /
/// OCCAS MBeans (Tuning reads/writes those directly), but JVM **profiles** and
/// their per-target assignments are persisted here, in
/// `config/custom/vorpal/blade-tuning.json`. The ServerStart baseline and
/// history live beside it (see [ServerStartSnapshot]).
@SchemaAbout(
		name = "Tuning",
		tagline = "OCCAS Performance Dashboard",
		description = "Edit JVM heap and GC, SIP protocol timers, WebLogic work-manager constraints, server thread pools, and cluster topology via JMX. Live read / write against the running domain.")
public class TuningSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected List<JvmProfile> jvmProfiles = new ArrayList<>();
	protected Map<String, String> jvmProfileAssignments = new LinkedHashMap<>();

	@JsonPropertyDescription("Named sets of JVM Server-Start arguments. Assign one to each target (a static server or a server template), then Apply to overlay it onto that target's ServerStart.Arguments.")
	public List<JvmProfile> getJvmProfiles() {
		return jvmProfiles;
	}

	public TuningSettings setJvmProfiles(List<JvmProfile> jvmProfiles) {
		this.jvmProfiles = jvmProfiles;
		return this;
	}

	@JsonPropertyDescription("Maps each target name (a static server such as engine0, or a server template) to the name of the JVM profile it uses.")
	public Map<String, String> getJvmProfileAssignments() {
		return jvmProfileAssignments;
	}

	public TuningSettings setJvmProfileAssignments(Map<String, String> jvmProfileAssignments) {
		this.jvmProfileAssignments = jvmProfileAssignments;
		return this;
	}
}
