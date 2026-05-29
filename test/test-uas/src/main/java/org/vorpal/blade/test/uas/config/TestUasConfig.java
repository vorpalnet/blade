package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Configuration for the BLADE Test UAS.
///
/// Response behavior is driven entirely by the Request-URI (`status`, `delay`,
/// `refer`), so this carries no app-specific settings — only the inherited
/// logging and session parameters. It exists as a concrete type for the
/// [org.vorpal.blade.framework.v2.config.SettingsManager].
public class TestUasConfig extends Configuration {

	public TestUasConfig() {
	}

}
