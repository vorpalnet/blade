package org.vorpal.blade.services.tpcc.v1.dialog;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipSession;

public class DialogConnectRequest {
	public String[] dialogs;

	public DialogConnectRequest() {
		// do nothing;
	}

	public DialogConnectRequest(SipSession a, SipSession b) {
		dialogs = new String[2];
		dialogs[0] = (String) a.getAttribute("X-Vorpal-Dialog");
		dialogs[1] = (String) b.getAttribute("X-Vorpal-Dialog");
	}

}