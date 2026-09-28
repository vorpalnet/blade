package org.vorpal.blade.services.proxy.balancer;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/// What the balancer tells the event bus: where each call went, and when an
/// endpoint's health changes. The health state stays on [EndpointHealth] and its
/// MBean; this only announces the transitions, so a dashboard or an alert hears
/// an endpoint go down without polling every node.
final class BalancerEvents {

	private BalancerEvents() {
	}

	/// The call was answered by `bobResponse`'s endpoint.
	static void routed(SipServletRequest aliceRequest, SipServletResponse bobResponse, String tier, int failovers) {
		SipServletRequest bobRequest = bobResponse.getRequest();
		Object endpoint = (bobRequest == null) ? null : bobRequest.getAttribute(InviteCallflow.ENDPOINT_NAME_ATTR);
		Events.publish(aliceRequest.getApplicationSession(), BladeEventTypes.CALL_ROUTED, data -> data
				.put("destination", (bobRequest == null) ? null : String.valueOf(bobRequest.getRequestURI()))
				.put("endpoint", (endpoint == null) ? null : endpoint.toString())
				.put("tier", tier)
				.put("failovers", failovers));
	}

	/// No tier connected the call; the caller is answered `status`.
	static void declined(SipServletRequest aliceRequest, int status, int failovers) {
		Events.publish(aliceRequest.getApplicationSession(), BladeEventTypes.CALL_DECLINED, data -> data
				.put("status", status)
				.put("failovers", failovers));
	}

	static void down(String endpoint, EndpointHealth health, Integer retryAfter) {
		Events.publish(BladeEventTypes.ENDPOINT_DOWN, endpoint, data -> data
				.put("endpoint", endpoint)
				.put("note", health.getNote())
				.put("retryAfter", retryAfter)
				.put("node", SettingsManager.getServerName()));
	}

	static void up(String endpoint, EndpointHealth health) {
		Events.publish(BladeEventTypes.ENDPOINT_UP, endpoint, data -> data
				.put("endpoint", endpoint)
				.put("note", health.getNote())
				.put("node", SettingsManager.getServerName()));
	}
}
