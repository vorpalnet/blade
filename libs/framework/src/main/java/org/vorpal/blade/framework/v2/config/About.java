package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "name", "tagline", "description", "notes" })
@FormLayoutGroup({ "name", "tagline" })
public class About implements Serializable {

	/// Display metadata — shown on the BLADE Admin Portal launcher deck and
	/// other consumers of app metadata. Admin apps populate these in their
	/// `*SettingsSample` constructor; services typically leave them null (the
	/// portal filters apps without a `name` out of its deck).
	protected String name;
	protected String tagline;
	protected String description;
	protected String notes;

	@JsonPropertyDescription("Short brand name for this app, shown as the heading on its Admin Portal launcher card and as the topbar product label inside the app.")
	public String getName() {
		return name;
	}

	public About setName(String name) {
		this.name = name;
		return this;
	}

	@JsonPropertyDescription("One-line subtitle shown under the name on the launcher card — a punchy descriptor of what the app does.")
	public String getTagline() {
		return tagline;
	}

	public About setTagline(String tagline) {
		this.tagline = tagline;
		return this;
	}

	@JsonPropertyDescription("Longer paragraph explaining what the app does, who it's for, and what it changes. Appears as the body text on the launcher card and in operator docs.")
	@FormLayout(wide = true, multiline = true)
	public String getDescription() {
		return description;
	}

	public About setDescription(String description) {
		this.description = description;
		return this;
	}

	@JsonPropertyDescription("Section for administrator notes regarding this configuration.")
	@FormLayout(wide = true, multiline = true)
	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

}
