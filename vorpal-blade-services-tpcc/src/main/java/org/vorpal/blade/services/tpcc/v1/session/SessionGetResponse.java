package org.vorpal.blade.services.tpcc.v1.session;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.services.tpcc.v1.dialog.Dialog;

import io.swagger.v3.oas.annotations.media.Schema;

public class SessionGetResponse {
	private Integer expires = null;

	public List<String> groups = new LinkedList<>();
	public Map<String, String> attributes = new HashMap<>();
	public Map<String, Dialog> dialogs = new HashMap<>();

	public SessionGetResponse() {
		// do nothing;
	}

	public SessionGetResponse(SipApplicationSession appSession) {
		String sessionId = (String) appSession.getAttribute("X-Vorpal-Session");

		for (String name : appSession.getAttributeNameSet()) {
			if (name.startsWith("3pcc_")) {
				attributes.put(name.replace("3pcc_", ""), (String) appSession.getAttribute(name));
			}
		}

		for (String group : appSession.getIndexKeys()) {
			if (group.equals(sessionId) == false) {
				groups.add(group);
			}
		}

		@SuppressWarnings("unchecked")
		Iterator<SipSession> itr = (Iterator<SipSession>) appSession.getSessions("SIP");

		SipSession sipSession;
		while (itr.hasNext()) {
			sipSession = itr.next();
			if (sipSession.isValid()) {

				String dialogId = (String) sipSession.getAttribute("X-Vorpal-Dialog");
				if (dialogId != null) {
					Dialog sessionDialog = new Dialog(sipSession);
					this.dialogs.put(dialogId, sessionDialog);
				}

			}
		}

	}

	public void setExpires(Integer expires) {
		this.expires = expires;
	}

	@Schema(type = "integer", //
			name = "expires", //
			description = "Session expiration due to inactivity, in minutes. Default is 3.", //
			example = "30", //
			examples = "{30}")
	public Integer getExpires() {
		return this.expires;
	}

	public SessionGetResponse addGroup(String group) {
		groups = (groups != null) ? groups : new LinkedList<>();
		groups.add(group);
		return this;
	}

	public SessionGetResponse addAttribute(String name, String value) {
		attributes = (attributes != null) ? attributes : new HashMap<>();
		attributes.put(name, value);
		return this;
	}

	public SessionGetResponse addDialog(String dialogId, Dialog dialog) {
		dialogs = (dialogs != null) ? dialogs : new HashMap<>();
		dialogs.put(dialogId, dialog);
		return this;
	}

}
