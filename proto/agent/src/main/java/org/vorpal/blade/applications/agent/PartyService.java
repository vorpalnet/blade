package org.vorpal.blade.applications.agent;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;

/// Asks for someone to be brought into a live call.
///
/// The console names a person from [AgentSettings#getPeople]; this looks up the
/// address and publishes `partyRequested` (`org.vorpal.blade.call.party.requested`)
/// on the call. It does not dial: the application holding the call's media, the
/// listener, does that on whichever node owns the call, and only for an address
/// its own `partyTargets` allow. This application never knows where a call's
/// media lives, and a browser never supplies an address.
final class PartyService {

	/// The analytics event name, which the application's analytics configuration must list.
	static final String EVENT = "partyRequested";

	private PartyService() {
	}

	/// Publish the request. Returns null when it was published, else why not.
	static String request(String vorpalId, String label, String agent) {
		AgentSettings cfg = AgentServlet.settings();
		String target = (cfg == null || label == null) ? null : cfg.getPeople().get(label);
		if (target == null) {
			return "no one called " + label + " can be brought in";
		}
		AgentConsoleRegistry.CallRef call = AgentConsoleRegistry.callRef(vorpalId);
		if (call == null) {
			return "the call is not on this node's record";
		}
		Analytics analytics = SettingsManager.getAnalytics();
		if (analytics == null) {
			return "analytics is not configured, so the request cannot be sent";
		}
		AnalyticsEvent event = new AnalyticsEvent(EVENT, call.vorpalId, call.startedAt);
		event.addAttribute("target", target);
		event.addAttribute("label", label);
		event.addAttribute("requestedBy", agent);
		analytics.sendEvent(event);
		return null;
	}
}
