package org.vorpal.blade.services.presence;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// The timers at their defaults. Skills are published by agents, so there are none to sample.
public class PresenceSettingsSample extends PresenceSettings {
	private static final long serialVersionUID = 1L;

	public PresenceSettingsSample() {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();
	}
}
