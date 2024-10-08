package org.vorpal.blade.services.tpcc.v1.dialog;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipSession;

public class DialogResponse {
	int status;
	String reasonPhrase;

	public Map<String, String> attributes = new HashMap<>();

	public DialogResponse() {
		// do nothing;
	}

	public DialogResponse(SipSession sipSession) {

		for (String name : sipSession.getAttributeNameSet()) {
			if (name.startsWith("3pcc_")) {
				attributes.put(name.replace("3pcc_", ""), (String) sipSession.getAttribute(name));
			}
		}

	}

}