package org.vorpal.blade.applications.javadoc;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Javadoc admin app. The site is static HTML, so this
/// exposes only the inherited `name` / `tagline` / `description` metadata
/// fields — read over JMX by the Admin Portal to render this app's launcher
/// card. See memory `[[admin-apps-use-settingsmanager]]`.
public class JavadocSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
