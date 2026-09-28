package org.vorpal.blade.applications.agent;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/// Asks for someone to be brought into a live call.
///
/// The console names a person from [AgentSettings#getPeople]; this looks up the
/// address and publishes [BladeEventTypes#CALL_PARTY_REQUESTED] on the call. It does not dial: the application holding the call's media, the
/// listener, does that on whichever node owns the call, and only for an address
/// its own `partyTargets` allow. This application never knows where a call's
/// media lives, and a browser never supplies an address.
final class PartyService {

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
		boolean sent = Events.publish(call.vorpalId, call.startedAt, BladeEventTypes.CALL_PARTY_REQUESTED,
				data -> data.put("target", target).put("label", label).put("requestedBy", agent));
		if (!sent) {
			return "the event bus is not up on this node, so the request cannot be sent";
		}
		return null;
	}
}
