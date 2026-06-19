package org.vorpal.blade.applications.console.tuning;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A named, complete set of JVM Server-Start arguments. Nodes reference a
/// profile by [#name]; [#arguments] is written verbatim into the assigned
/// node's `ServerStart.Arguments` when the profile is applied.
public class JvmProfile implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String name = "";
	protected String arguments = "";

	public JvmProfile() {
	}

	public JvmProfile(String name, String arguments) {
		this.name = name;
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

	@JsonPropertyDescription("The full JVM argument string, written verbatim to ServerStart.Arguments on apply.")
	public String getArguments() {
		return arguments;
	}

	public JvmProfile setArguments(String arguments) {
		this.arguments = arguments;
		return this;
	}
}
