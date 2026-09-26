package org.vorpal.blade.services.webrtc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Which browser on this node holds each call, by the call's Vorpal-ID.
///
/// An application behind the gateway addresses a browser the only way it can: by the Vorpal-ID
/// the call carries end to end (the gateway stamps it on the INVITE it places, and a trusted hop
/// adopts it). [FarSideEvents] turns that back into the browser's address and its own handle for
/// the call. Node-local, like [BrowserRegistry]: the socket is on this node or it is not.
final class BrowserCalls {

	/// A browser's call: the address holding the socket, and the call id the browser knows it by.
	static final class Call {
		final String aor;
		final String callId;

		Call(String aor, String callId) {
			this.aor = aor;
			this.callId = callId;
		}
	}

	private static final Map<String, Call> BY_VORPAL_ID = new ConcurrentHashMap<>();

	private BrowserCalls() {
	}

	/// Record that `callId`, held by the browser at `aor`, is the call with `vorpalId`.
	static void remember(String vorpalId, String aor, String callId) {
		if (vorpalId != null && aor != null && callId != null) {
			BY_VORPAL_ID.put(vorpalId, new Call(aor, callId));
		}
	}

	/// The browser call with `vorpalId` on this node, or null.
	static Call find(String vorpalId) {
		return (vorpalId == null) ? null : BY_VORPAL_ID.get(vorpalId);
	}

	/// The call `callId` ended.
	static void forget(String callId) {
		if (callId != null) {
			BY_VORPAL_ID.values().removeIf(call -> callId.equals(call.callId));
		}
	}
}
