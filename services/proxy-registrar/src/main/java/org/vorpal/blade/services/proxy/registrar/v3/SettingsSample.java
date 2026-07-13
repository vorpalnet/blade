package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class SettingsSample extends Settings implements Serializable {

	private static final long serialVersionUID = 1L;

	public SettingsSample() {
		// v3 config shape — see Settings.getVersion().
		this.setVersion(3);
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();
		this.session.setSessionSelectors(null);
		// drop out of the dialog after call setup — this is the 'proxy' in
		// Proxy Registrar; set false to stay in the path as a full B2BUA
		this.session.setPassthru(true);
		this.logging.setLoggingLevel(LoggingLevel.INFO);

		this.setAllowHeader("INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, MESSAGE, OPTIONS, INFO, SUBSCRIBE");
		this.setProxyOnUnregistered(false);
		this.setTimeout(180);
	}

}
