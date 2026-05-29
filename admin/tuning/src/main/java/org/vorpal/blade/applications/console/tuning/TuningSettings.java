package org.vorpal.blade.applications.console.tuning;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Tuning admin app. Currently exposes only the inherited
/// `name` / `tagline` / `description` metadata fields (rendered on the BLADE Admin Portal launcher card). The
/// app's per-knob settings live in WLS / OCCAS MBeans, not here — Tuning
/// reads and writes those directly.
public class TuningSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
