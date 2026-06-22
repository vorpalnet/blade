package org.vorpal.blade.applications.console.tuning;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A named, complete set of JVM Server-Start arguments. Nodes reference a
/// profile by [#name]; [#arguments] is written verbatim into the assigned
/// node's `ServerStart.Arguments` when the profile is applied. [#about] is
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

	@JsonPropertyDescription("Profile name — what each node references to pick its JVM arguments.")
	public String getName() {
		return name;
	}

	public JvmProfile setName(String name) {
		this.name = name;
		return this;
	}

	@JsonPropertyDescription("Free-text description of what this profile is for. Descriptive only — never applied to a node.")
	public String getAbout() {
		return about;
	}

	public JvmProfile setAbout(String about) {
		this.about = about;
		return this;
	}

	@JsonPropertyDescription("The full JVM argument string, written verbatim to ServerStart.Arguments on apply.")
	public String getArguments() {
		return arguments;
	}

	public JvmProfile setArguments(String arguments) {
		this.arguments = arguments;
		return this;
	}

	@JsonPropertyDescription("When true, the editor auto-sizes Metaspace/MaxMetaspaceSize from Max Heap. UI state only — the computed values are already baked into the arguments string applied to a node.")
	public boolean isMetaAuto() {
		return metaAuto;
	}

	public JvmProfile setMetaAuto(boolean metaAuto) {
		this.metaAuto = metaAuto;
		return this;
	}
}
