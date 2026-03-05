package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;

public class AnalyticsTransferSample extends AnalyticsB2buaSample implements Serializable {

	private static final long serialVersionUID = 1L;

	public AnalyticsTransferSample() {

		EventSelector transferRequested = createEventSelector("transferRequested");
		transferRequested.addAttribute("transferor", "Refered-By", "^.*sip:(.*)@.*$", "$1");
		transferRequested.addAttribute("transferee", "To", "^.*sip:(.*)@.*$", "$1");
		transferRequested.addAttribute("target", "Refer-To", "^.*sip:(.*)@.*$", "$1");

		EventSelector transferInitiated = createEventSelector("transferInitiated");
		transferInitiated.addAttribute("original", "X-Original-DN", "^.*sip:(.*)@.*$", "$1");
		transferInitiated.addAttribute("previous", "X-Previous-DN", "^.*sip:(.*)@.*$", "$1");

		EventSelector transferDeclined = createEventSelector("transferDeclined");
		transferDeclined.addAttribute("status", "status", "^.*$", "$0");
		transferDeclined.addAttribute("reason", "reason", "^.*$", "$0");

		EventSelector transfer = createEventSelector("/v1/transfer");
		transfer.addAttribute("style", "$.style", "^.*$", "$0");
		transfer.addAttribute("sessionKey", "$.sessionKey", "^.*$", "$0");
		transfer.addAttribute("target", "$.target.sipUri", "^.*$", "$0");

	}

}
