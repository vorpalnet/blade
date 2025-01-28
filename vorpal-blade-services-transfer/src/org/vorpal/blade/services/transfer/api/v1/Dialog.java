package org.vorpal.blade.services.transfer.api.v1;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class Dialog {
	public String requestUri;
	public String remoteParty;
	public Map<String, List<String>> inviteHeaders = new HashMap<>();
	public String content = null;

	public Dialog() {
		// do nothing;
	}

	public Dialog(SipSession sipSession) {

		SipServletRequest initialInvite = (SipServletRequest) sipSession.getAttribute("initial_invite");

		for (String name : initialInvite.getHeaderNameList()) {
			inviteHeaders.put(name, initialInvite.getHeaderList(name));
		}

		try {
			Object objContent = initialInvite.getContent();

			if (objContent != null) {

				byte[] rawContent;

				if (objContent instanceof String) {
					rawContent = ((String)objContent).getBytes();
				} else {
					rawContent = (byte[]) objContent;
				}
				
	            // System.setProperty("mail.mime.encodeeol.strict", "true");
				
				content = Base64.getMimeEncoder().encodeToString(rawContent);
				Callflow.getSipLogger().finer(sipSession, "content = "+content);
			}

		} catch (Exception e) {
			Callflow.getSipLogger().severe(e);
		}

	}

}