package org.vorpal.blade.framework.v2.analytics;

public class AnalyticsTransferSample extends AnalyticsB2buaSample {

	public AnalyticsTransferSample() {

		EventSelector callStarted = createEventSelector("transferRequested");
		callStarted.addAttribute("transferor", "Refered-By", "sip:(.*)@.*", "$1");
		callStarted.addAttribute("transferee", "To", "sip:(.*)@.*", "$1");
		callStarted.addAttribute("target", "Refer-To", "sip:(.*)@.*", "$1");

		EventSelector transferInitiated = createEventSelector("transferInitiated");
		transferInitiated.addAttribute("original", "X-Original-DN", "sip:(.*)@.*", "$1");
		transferInitiated.addAttribute("previous", "X-Previous-DN", "sip:(.*)@.*", "$1");

		EventSelector transferDeclined = createEventSelector("transferDeclined");
		transferDeclined.addAttribute("status", "status", "^.*$", "$0");
		transferDeclined.addAttribute("reason", "reason", "^.*$", "$0");

	}

}
