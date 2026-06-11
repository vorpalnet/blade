package org.vorpal.blade.applications.logs;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Logs admin app. Currently exposes only the inherited
/// `name` / `tagline` / `description` metadata fields (rendered on the BLADE Admin Portal launcher card). Add
/// app-specific knobs here later (e.g. default follow window, max history).
@SchemaAbout(
		name = "Logs",
		tagline = "Cluster Log Tail",
		description = "Stream and filter logs from every node in the cluster. Merges per-server output by timestamp; supports phase filters, severity filters, and live follow mode.")
public class LogsSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
