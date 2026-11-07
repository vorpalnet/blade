package org.vorpal.blade.services.presence;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.Callflow;

/// Watch an agent (RFC 6665, event package `presence`): `200`, then a NOTIFY with their state now,
/// and another on every change ([Agents#notifyWatchers]). A refresh in the same dialog extends it;
/// `Expires: 0` ends it with a final NOTIFY.
public class SubscribeCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		String event = request.getHeader("Event");
		if (event == null || !event.trim().toLowerCase().startsWith("presence")) {
			SipServletResponse bad = request.createResponse(489);
			bad.setHeader("Allow-Events", "presence");
			sendResponse(bad);
			return;
		}
		PresenceSettings settings = PresenceServlet.settingsManager.getCurrent();
		int expires = request.getExpires();
		if (expires < 0 || expires > settings.getMaxExpiresSeconds()) {
			expires = settings.getMaxExpiresSeconds();
		}
		String address = PresenceServlet.getAccountName(request.getTo());
		SipApplicationSession app = request.getApplicationSession();
		long now = System.currentTimeMillis();
		Agent agent = Agents.of(app, address);
		agent.prune(now);

		String dialog = request.getSession().getId();
		if (expires == 0) {
			agent.watchers.remove(dialog);
		} else {
			agent.watchers.put(dialog, now + expires * 1000L);
		}
		Agents.save(app, agent, now);

		SipServletResponse ok = request.createResponse(200);
		ok.setExpires(expires);
		sendResponse(ok);
		Agents.notify(request.getSession(), agent, now, expires);
	}
}
