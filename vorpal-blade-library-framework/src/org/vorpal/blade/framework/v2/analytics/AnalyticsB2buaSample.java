package org.vorpal.blade.framework.v2.analytics;

import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;

public class AnalyticsB2buaSample extends AnalyticsAsyncSipServletSample {

	public AnalyticsB2buaSample() {
		
		EventSelector callStarted = createEventSelector("callStarted");
		callStarted.addAttribute("caller", "From", "^.*sip:(.*)@.*$", "$1");
		callStarted.addAttribute("callee", "To", "^.*sip:(.*)@.*$", "$1");
		callStarted.addAttribute("requestUri", "RequestURI", "^.*$", "$0");
		callStarted.addAttribute("ani", "From","^.*sip:(.*)@.*$", "$1").setDialog(DialogType.origin);
		callStarted.addAttribute("did", "To", "^.*sip:(.*)@.*$", "$1").setDialog(DialogType.origin);

		EventSelector callCompleted = createEventSelector("callCompleted");
		callCompleted.addAttribute("disconnector", "From", "^.*sip:(.*)@.*$", "$1");

		EventSelector callDeclined = createEventSelector("callDeclined");
		callDeclined.addAttribute("status", "status", "^.*$", "$0");
		callDeclined.addAttribute("reason", "reason", "^.*$", "$0");

	}

}
