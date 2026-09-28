package org.vorpal.blade.services.messaging.v3;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Default configuration written on first deployment.
///
/// The event bus is left unset, so it is used wherever the domain provisions it. Room
/// membership arrives on it, so a messaging service on a domain without a bus has no members
/// and delivers nothing.
public class MessagingSettingsSample extends MessagingSettings {
	private static final long serialVersionUID = 1L;

	public MessagingSettingsSample() {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();
	}
}
