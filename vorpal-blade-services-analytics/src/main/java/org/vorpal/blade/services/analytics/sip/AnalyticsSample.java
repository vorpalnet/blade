package org.vorpal.blade.services.analytics.sip;

import java.util.logging.Level;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.analytics.EventSelector;
import org.vorpal.blade.framework.v2.config.Configuration;

public class AnalyticsSample extends Analytics {

	public AnalyticsSample() {

		this.level = Level.modest;
		
		this.jmsFactory = "jms/TestConnectionFactory";
		this.jmsQueue = "jms/TestJMSQueue";

		EventSelector callStarted = createEventSelector("callStarted");
		callStarted.addAttribute("From", "From", "sip:(.*)@.*", "$1");
		callStarted.addAttribute("To", "To", "sip:(.*)@.*", "$1");
		callStarted.addAttribute("RURI", "RequestURI", "^.*$", "$0");
		callStarted.addAttribute("ANI", "From", Configuration.SIP_ADDRESS_PATTERN, "${user}");
		callStarted.addAttribute("DID", "To", Configuration.SIP_ADDRESS_PATTERN, "${user}");

		EventSelector callDeclined = createEventSelector("callDeclined");
		callDeclined.addAttribute("status", "status", "^.*$", "$0");


	}

}
