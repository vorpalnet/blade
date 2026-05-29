package org.vorpal.blade.applications.logs;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Logs admin app. Currently exposes only the inherited
/// `name` / `tagline` / `description` metadata fields (rendered on the BLADE Admin Portal launcher card). Add
/// app-specific knobs here later (e.g. default follow window, max history).
public class LogsSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
