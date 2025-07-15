package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class Dialog implements Serializable {
	public String id;
	public String requestUri;
	public String remoteParty;
	public Map<String, List<String>> inviteHeaders = new HashMap<>();
	public String content = null;

	public Dialog() {
		// do nothing;
	}

	public Dialog(SipSession sipSession) {
		try {

			
			id = (String) sipSession.getAttribute("X-Vorpal-Dialog");

			SipServletRequest initialInvite = (SipServletRequest) sipSession.getAttribute("initial_invite");

			for (String name : initialInvite.getHeaderNameList()) {
				inviteHeaders.put(name, initialInvite.getHeaderList(name));
			}

			Object objContent = initialInvite.getContent();
			if (objContent != null) {
				byte[] rawContent;

				if (objContent instanceof String) {
					rawContent = ((String) objContent).getBytes();
				} else {
					rawContent = (byte[]) objContent;
				}

				content = Base64.getMimeEncoder().encodeToString(rawContent);
			}
			
//			Object obj;
//			for(String name: sipSession.getAttributeNameSet()) {
//				obj = sipSession.
//			}
			
			

		} catch (Exception e) {
			Callflow.getSipLogger().severe(e);
		}

	}

}