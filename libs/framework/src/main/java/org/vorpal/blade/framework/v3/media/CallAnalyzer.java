package org.vorpal.blade.framework.v3.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// Something that reasons about a call while an application hears it: told when the call starts,
/// every time a party says something, every time a voice is scored, and when the call ends.
///
/// The application that anchors the media calls every installed analyzer on the node that owns
/// the call, so an analyzer's state for a call lives in one place and needs no coordination with
/// other nodes. What an analyzer concludes it publishes itself, usually as events on the bus.
///
/// ## Discovery
///
/// Found through [ServiceLoader], like [org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive].
/// With none on the classpath the application hears the call and publishes what it heard, and
/// nothing else happens.
///
/// ## Threads
///
/// [#heard] and [#voiceAssessed] run on a media driver thread, which must not block. An analyzer
/// hands anything slow, a database query or a network call, to a thread of its own. An exception
/// thrown from any method is logged and never reaches the call.
public interface CallAnalyzer {

	/// The call's first INVITE, as it arrived. `party` names used later are `caller` and `callee`.
	default void callStarted(SipApplicationSession app, SipServletRequest invite) {
	}

	/// A party said something. `live` is the first decode ([TranscriberEvent.Type#LIVE_UTTERANCE]);
	/// otherwise it is the decode for the record.
	default void heard(SipApplicationSession app, String party, Utterance utterance, boolean live) {
	}

	/// One window of a party's voice was scored ([VoiceAssessmentEvent]).
	default void voiceAssessed(SipApplicationSession app, String party, double score, String model) {
	}

	/// The call ended, however it ended. Called once per call that [#callStarted] saw.
	default void callEnded(SipApplicationSession app) {
	}

	/// Every analyzer on the classpath, in no particular order. Empty when there are none.
	static List<CallAnalyzer> installed() {
		List<CallAnalyzer> found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	final class Holder {
		static volatile List<CallAnalyzer> cached;

		private Holder() {
		}

		static List<CallAnalyzer> load() {
			List<CallAnalyzer> found = new ArrayList<>();
			try {
				for (CallAnalyzer analyzer : ServiceLoader.load(CallAnalyzer.class, CallAnalyzer.class.getClassLoader())) {
					found.add(analyzer);
				}
			} catch (Throwable t) {
				Logger.getLogger(CallAnalyzer.class.getName()).log(Level.SEVERE, "a CallAnalyzer could not be loaded", t);
			}
			return Collections.unmodifiableList(found);
		}
	}
}
