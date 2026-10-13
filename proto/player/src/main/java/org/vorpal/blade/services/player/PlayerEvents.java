package org.vorpal.blade.services.player;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/// The call lifecycle this application publishes: started, answered, and one
/// end (completed, abandoned or declined).
///
/// A B2BUA gets these from the framework's callflow. This application answers
/// calls itself, so it says them itself, or analytics sees its calls only as
/// sessions with nothing in them and reports it as never sending a start, an
/// answer or an end.
final class PlayerEvents {

	/// Set once an end has been published, so a call has exactly one: the
	/// application's own BYE after playback and the caller's BYE crossing it
	/// would otherwise both say so.
	private static final String ENDED = "player.ended";

	private PlayerEvents() {
	}

	static void started(SipServletRequest invite) {
		Events.publish(invite.getApplicationSession(), BladeEventTypes.CALL_STARTED, data -> data
				.put("caller", user(invite.getFrom().getURI()))
				.put("destination", user(invite.getTo().getURI())));
	}

	static void answered(SipApplicationSession app) {
		Events.publish(app, BladeEventTypes.CALL_ANSWERED, null);
	}

	/// The call ended: completed after an answer, abandoned before one.
	///
	/// @param type [BladeEventTypes#CALL_COMPLETED], [BladeEventTypes#CALL_ABANDONED]
	///             or [BladeEventTypes#CALL_DECLINED]
	static void ended(SipApplicationSession app, String type) {
		if (app == null || !app.isValid() || app.getAttribute(ENDED) != null) {
			return;
		}
		app.setAttribute(ENDED, Boolean.TRUE);
		Events.publish(app, type, null);
	}

	private static String user(URI uri) {
		if (uri instanceof SipURI) {
			String user = ((SipURI) uri).getUser();
			return (user == null || user.isEmpty()) ? null : user;
		}
		return null;
	}
}
