package org.vorpal.blade.applications.console.tuning;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Tuning admin app. Currently exposes only the inherited
/// `name` / `tagline` / `description` metadata fields (rendered on the BLADE Admin Portal launcher card). The
/// app's per-knob settings live in WLS / OCCAS MBeans, not here — Tuning
/// reads and writes those directly.
@SchemaAbout(
		name = "Tuning",
		tagline = "OCCAS Performance Dashboard",
		description = "Edit JVM heap and GC, SIP protocol timers, WebLogic work-manager constraints, server thread pools, cluster topology, Node Manager, and SSO settings via JMX. Live read / write against the running domain.")
public class TuningSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
