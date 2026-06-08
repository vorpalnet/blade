package org.vorpal.blade.applications.testconsole;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Test Console admin app. The console is a pure JMX
/// dashboard — every tester node is discovered at runtime — so this carries
/// only the inherited `about` metadata (for the Admin Portal launcher deck)
/// and logging parameters.
public class TestConsoleSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	public TestConsoleSettings() {
	}
}
