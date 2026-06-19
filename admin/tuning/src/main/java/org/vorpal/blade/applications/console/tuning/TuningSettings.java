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
/// their per-node assignments are persisted here, in
/// `config/custom/vorpal/tuning.json`.
@SchemaAbout(
		name = "Tuning",
		tagline = "OCCAS Performance Dashboard",
		description = "Edit JVM heap and GC, SIP protocol timers, WebLogic work-manager constraints, server thread pools, and cluster topology via JMX. Live read / write against the running domain.")
public class TuningSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected List<JvmProfile> jvmProfiles = new ArrayList<>();
	protected Map<String, String> jvmProfileAssignments = new LinkedHashMap<>();

	@JsonPropertyDescription("Named, complete sets of JVM Server-Start arguments. Assign one to each node, then Apply to write it into that node's ServerStart.Arguments.")
	public List<JvmProfile> getJvmProfiles() {
		return jvmProfiles;
	}

	public TuningSettings setJvmProfiles(List<JvmProfile> jvmProfiles) {
		this.jvmProfiles = jvmProfiles;
		return this;
	}

	@JsonPropertyDescription("Maps each server name to the name of the JVM profile it uses.")
	public Map<String, String> getJvmProfileAssignments() {
		return jvmProfileAssignments;
	}

	public TuningSettings setJvmProfileAssignments(Map<String, String> jvmProfileAssignments) {
		this.jvmProfileAssignments = jvmProfileAssignments;
		return this;
	}
}
