package org.vorpal.blade.services.transfer.api.v1;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;

import io.swagger.v3.oas.annotations.media.Schema;

public class SessionResponse implements Serializable {
	private Integer expires = null;

	public String id = null;
	public List<String> groups = new LinkedList<>();
	public Map<String, String> attributes = new HashMap<>();
	public Map<String, Dialog> dialogs = new HashMap<>();

	public SessionResponse() {
		// do nothing;
	}

	public SessionResponse(SipApplicationSession appSession) {
		Object obj;

		// set the session id
		id = (String) appSession.getAttribute("X-Vorpal-Session");

		// set any session variables (String)
		for (String name : appSession.getAttributeNameSet()) {
			obj = appSession.getAttribute(name);
			if (obj instanceof String) {
				attributes.put(name, (String) obj);
			}
		}

		// set any groups (index keys) that are different than the id
		for (String group : appSession.getIndexKeys()) {
			if (group.equals(id) == false) {
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

	public SessionResponse addGroup(String group) {
		groups = (groups != null) ? groups : new LinkedList<>();
		groups.add(group);
		return this;
	}

	public SessionResponse addAttribute(String name, String value) {
		attributes = (attributes != null) ? attributes : new HashMap<>();
		attributes.put(name, value);
		return this;
	}

	public SessionResponse addDialog(String dialogId, Dialog dialog) {
		dialogs = (dialogs != null) ? dialogs : new HashMap<>();
		dialogs.put(dialogId, dialog);
		return this;
	}

}
