package org.vorpal.blade.services.tpcc.v1.dialog;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipSession;

public class DialogRequest{
	public String callbackUrl; // not implemented
	public String requestUri;
	public String remoteParty;
	public String localParty;
	public Map<String, String> attributes = new HashMap<>();

	public DialogRequest() {
		// do nothing;
	}

//	public Dialog(SipSession sipSession) {
//		this.remoteParty = sipSession.getRemoteParty().toString();
//
//		for (String name : sipSession.getAttributeNameSet()) {
//			if (name.startsWith("3pcc_")) {
//				attributes.put(name.replace("3pcc_", ""), (String) sipSession.getAttribute(name));
//			}
//		}
//	}

}