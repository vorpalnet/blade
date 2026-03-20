package org.vorpal.blade.services.tpcc.v1.dialog;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipSession;

public class DialogProperties {
	public String remoteParty;
	public Map<String, String> attributes;

	public DialogProperties(SipSession sipSession) {

		remoteParty = sipSession.getRemoteParty().toString();

		attributes = new HashMap<>();
		for (String name : sipSession.getAttributeNameSet()) {
			if (name.startsWith("3pcc_")) {
				attributes.put(name.replace("3pcc_", ""), (String) sipSession.getAttribute(name));
			}
		}

	}

}
