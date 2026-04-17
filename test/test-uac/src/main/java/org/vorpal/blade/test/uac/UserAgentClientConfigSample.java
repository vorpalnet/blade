package org.vorpal.blade.test.uac;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class UserAgentClientConfigSample extends UserAgentClientConfig {

	private static final long serialVersionUID = 1L;

	public UserAgentClientConfigSample() {

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParametersDefault();
		this.session.setExpiration(180);

		// Applied to outbound INVITEs on inbound softphone calls.
		// Operators copy samples/invite-template.txt → _templates/invite-template.txt.
		this.template = "invite-template.txt";

		this.fromAddressPattern = "sip:load-${index}@blade.test";
		this.toAddressPattern = "sip:target@uas.test";
		this.requestUriTemplate = "sip:target@uas.test";
		this.duration = "30s";

	}
}
