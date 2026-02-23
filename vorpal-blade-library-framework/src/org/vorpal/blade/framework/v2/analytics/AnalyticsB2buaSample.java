package org.vorpal.blade.framework.v2.analytics;

import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;

public class AnalyticsB2buaSample extends AnalyticsAsyncSipServletSample {

	public AnalyticsB2buaSample() {
		
		EventSelector callStarted = createEventSelector("callStarted");
		callStarted.addAttribute("caller", "From", "sip:(.*)@.*", "$1");
		callStarted.addAttribute("callee", "To", "sip:(.*)@.*", "$1");
		callStarted.addAttribute("RURI", "RequestURI", "^.*$", "$0");
		callStarted.addAttribute("ANI", "From", Configuration.SIP_ADDRESS_PATTERN, "${user}")
				.setDialog(DialogType.origin);
		callStarted.addAttribute("DID", "To", Configuration.SIP_ADDRESS_PATTERN, "${user}")
				.setDialog(DialogType.origin);

		EventSelector callCompleted = createEventSelector("callCompleted");
		callCompleted.addAttribute("disconnector", "From", "sip:(.*)@.*", "$1").setDialog(DialogType.origin);

		EventSelector callDeclined = createEventSelector("callDeclined");
		callDeclined.addAttribute("status", "status", "^.*$", "$0");
		callDeclined.addAttribute("reason", "reason", "^.*$", "$0");

	}

}
