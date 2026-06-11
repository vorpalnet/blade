package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Operator-editable administrator notes attached to a configuration.
///
/// App **identity** — name, tagline, description — used to live here too, but it
/// is developer-owned and was being silently blanked whenever an operator saved
/// a config. It now lives in the generated JSON Schema via [SchemaAbout]; only
/// `notes` (genuine per-deployment operator data) remains here.
public class About implements Serializable {

	protected String notes;

	@JsonPropertyDescription("Section for administrator notes regarding this configuration.")
	@FormLayout(wide = true, multiline = true)
	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

}
