package org.vorpal.blade.applications.javadoc;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Javadoc admin app. The site is static HTML, so this holds
/// no config of its own — it exists only to carry the [SchemaAbout] identity
/// the Admin Portal reads over JMX (from the generated schema) to render this
/// app's launcher card. See memory `[[admin-apps-use-settingsmanager]]`.
@SchemaAbout(
		name = "API Reference",
		tagline = "BLADE Javadoc",
		description = "Browse the generated API documentation for every BLADE module — framework, libraries, admin tools, and services — in one place.")
public class JavadocSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
