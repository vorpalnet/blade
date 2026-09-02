package org.vorpal.blade.applications.console.tuning;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A named set of JVM Server-Start arguments. Targets reference a profile by
/// [#name]; on apply [#arguments] is overlaid onto the target's
/// `ServerStart.Arguments` knob by knob (a profile token replaces the existing
/// token with the same key; everything else on the line is kept). [#about] is
/// free-text describing what the profile is for; it is never applied to a node.
public class JvmProfile implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String name = "";
	protected String about = "";
	protected String arguments = "";
	protected boolean metaAuto = false;

	public JvmProfile() {
	}

	public JvmProfile(String name, String arguments) {
		this.name = name;
		this.arguments = arguments;
	}

	public JvmProfile(String name, String about, String arguments) {
		this.name = name;
		this.about = about;
		this.arguments = arguments;
	}

	@JsonPropertyDescription("Profile name: what each target references to pick its JVM arguments.")
	public String getName() {
		return name;
	}

	public JvmProfile setName(String name) {
		this.name = name;
		return this;
	}

	@JsonPropertyDescription("Free-text description of what this profile is for. Descriptive only, never applied to a target.")
	public String getAbout() {
		return about;
	}

	public JvmProfile setAbout(String about) {
		this.about = about;
		return this;
	}

	@JsonPropertyDescription("The JVM arguments this profile sets. On apply each is overlaid onto the target's ServerStart.Arguments by knob; arguments the profile does not mention are kept.")
	public String getArguments() {
		return arguments;
	}

	public JvmProfile setArguments(String arguments) {
		this.arguments = arguments;
		return this;
	}

	@JsonPropertyDescription("When true, the editor auto-sizes Metaspace/MaxMetaspaceSize from Max Heap. UI state only; the computed values are already baked into the arguments string applied to a node.")
	public boolean isMetaAuto() {
		return metaAuto;
	}

	public JvmProfile setMetaAuto(boolean metaAuto) {
		this.metaAuto = metaAuto;
		return this;
	}
}
