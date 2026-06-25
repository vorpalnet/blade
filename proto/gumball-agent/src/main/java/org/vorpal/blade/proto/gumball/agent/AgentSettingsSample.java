// Gumball Agent — proto. MIT License, (c) 2026 Vorpal Networks, LLC.
package org.vorpal.blade.proto.gumball.agent;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class AgentSettingsSample extends AgentSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	public AgentSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.WARNING);
		this.session = new SessionParametersDefault();

		kurentoUrl = "ws://localhost:8888/kurento";
		bridgeMode = "loopback"; // default: rig-testable against a stock Kurento; switch to "agent" for AI
		systemPrompt = "You are a friendly appointment-booking agent. Greet the caller, collect the "
				+ "reason for their visit and a preferred time, confirm the booking, and end the call.";
		language = "en-US";
		transferTarget = "sip:queue@example.com";
	}

}
