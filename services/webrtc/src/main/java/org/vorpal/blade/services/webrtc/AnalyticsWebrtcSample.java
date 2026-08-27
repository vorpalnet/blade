package org.vorpal.blade.services.webrtc;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.analytics.AnalyticsAsyncSipServletSample;
import org.vorpal.blade.framework.v2.analytics.EventSelector;

/// Which parts of a browser call end up in the analytics record.
///
/// Attribute selectors only decorate; a fact publishes with or without one, carrying just its name,
/// correlator and `dialog`. These exist so that an operator who turns analytics on gets rows that say
/// something rather than rows that merely count.
///
/// **No `DialogType.origin` selectors here, unlike `AnalyticsB2buaSample`.** That sample is written
/// for a back-to-back agent, which holds two linked SIP dialogs and can therefore ask for an
/// attribute from a specific one. This gateway holds exactly one SIP dialog per call — the other
/// party is a browser on a WebSocket, which has no dialog, no headers and nothing to select from.
/// A dialog-typed selector would simply never match.
public class AnalyticsWebrtcSample extends AnalyticsAsyncSipServletSample implements Serializable {

	private static final long serialVersionUID = 1L;

	public AnalyticsWebrtcSample() {

		// Who called whom. On an inbound call the browser is the callee and these come off the
		// registrar's fork; on an outbound call the browser is the caller and `To` is what it dialled.
		EventSelector callStarted = createEventSelector("callStarted");
		callStarted.addAttribute("caller", "From", "^.*sip:(.*)@.*$", "$1");
		callStarted.addAttribute("callee", "To", "^.*sip:(.*)@.*$", "$1");
		callStarted.addAttribute("requestUri", "RequestURI", "^.*$", "$0");

		// Why it did not connect. The most useful two fields on the most-asked-about event.
		EventSelector callDeclined = createEventSelector("callDeclined");
		callDeclined.addAttribute("status", "status", "^.*$", "$0");
		callDeclined.addAttribute("reason", "reason", "^.*$", "$0");

		// Which side hung up first, taken from the party that sent the BYE.
		EventSelector callCompleted = createEventSelector("callCompleted");
		callCompleted.addAttribute("disconnector", "From", "^.*sip:(.*)@.*$", "$1");

		EventSelector callAbandoned = createEventSelector("callAbandoned");
		callAbandoned.addAttribute("disconnector", "From", "^.*sip:(.*)@.*$", "$1");
	}
}
