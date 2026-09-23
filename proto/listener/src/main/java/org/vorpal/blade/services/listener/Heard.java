package org.vorpal.blade.services.listener;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// What the listener heard, published on the event bus.
///
/// Two facts, both call-scoped and correlated by the call's Vorpal-ID:
///
/// - `callerSaid` (`org.vorpal.blade.call.utterance`): a party said something,
///   as first decoded. Attributes `text`, `party` (caller or callee),
///   `startMs` and `endMs` on the transcription's clock. The text is the
///   redacted rendition when redaction is on, because the bus is read by more
///   than the screen it was meant for, and the analytics sink keeps it.
/// - `voiceAssessed` (`org.vorpal.blade.call.voice.assessed`): one scored
///   window of a party's voice. Attributes `score`, `party`, `model` and
///   `offsetMs`.
///
/// Each is an event name the application's analytics configuration must list
/// for it to leave the node. A failure to publish is swallowed: a screen that
/// misses a line is worth less than the call.
final class Heard {

	static final String UTTERANCE = "callerSaid";
	static final String VOICE = "voiceAssessed";

	private Heard() {
	}

	static void utterance(SipApplicationSession app, Utterance utterance) {
		Analytics analytics = SettingsManager.getAnalytics();
		String text = (utterance.getRedacted() != null) ? utterance.getRedacted() : utterance.getText();
		if (analytics == null || text == null || text.trim().isEmpty()) {
			return;
		}
		try {
			AnalyticsEvent event = new AnalyticsEvent(UTTERANCE, Analytics.getVorpalId(app),
					Analytics.getCallStartedAt(app));
			event.addAttribute("text", text.trim());
			event.addAttribute("party", utterance.getParty());
			event.addAttribute("startMs", String.valueOf(utterance.getStartMillis()));
			event.addAttribute("endMs", String.valueOf(utterance.getEndMillis()));
			analytics.sendEvent(event);
		} catch (Throwable t) {
			// See the class comment.
		}
	}

	static void voice(SipApplicationSession app, String party, double score, String model, long offsetMillis) {
		Analytics analytics = SettingsManager.getAnalytics();
		if (analytics == null) {
			return;
		}
		try {
			AnalyticsEvent event = new AnalyticsEvent(VOICE, Analytics.getVorpalId(app),
					Analytics.getCallStartedAt(app));
			event.addAttribute("score", String.format("%.3f", score));
			event.addAttribute("party", party);
			if (model != null && !model.isEmpty()) {
				event.addAttribute("model", model);
			}
			event.addAttribute("offsetMs", String.valueOf(offsetMillis));
			analytics.sendEvent(event);
		} catch (Throwable t) {
			// See the class comment.
		}
	}
}
