package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/**
 * Represents a SIP dialog for REST API responses.
 *
 * <p>Contains dialog identification, initial INVITE headers, and
 * Base64-encoded message body content.
 */
public class Dialog implements Serializable {
	private static final long serialVersionUID = 1L;

	// Session attribute keys
	private static final String X_VORPAL_DIALOG_ATTR = "X-Vorpal-Dialog";
	private static final String INITIAL_INVITE_ATTR = "initial_invite";

	public String id;
	public String requestUri;
	public String remoteParty;
	public Map<String, List<String>> inviteHeaders = new HashMap<>();
	public String content = null;

	public Dialog() {
		// Default constructor
	}

	public Dialog(SipSession sipSession) {
		if (sipSession == null) {
			return;
		}

		try {
			id = (String) sipSession.getAttribute(X_VORPAL_DIALOG_ATTR);

			SipServletRequest initialInvite = (SipServletRequest) sipSession.getAttribute(INITIAL_INVITE_ATTR);
			if (initialInvite == null) {
				return;
			}

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

		} catch (Exception e) {
			Callflow.getSipLogger().severe(e);
		}

	}

}