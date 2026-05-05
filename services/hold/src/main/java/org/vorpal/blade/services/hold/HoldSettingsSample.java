package org.vorpal.blade.services.hold;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class HoldSettingsSample extends HoldSettings {

	private static final long serialVersionUID = 1L;

	public HoldSettingsSample() {

		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();
	}
}
