package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.EventType;
import javax.media.mscontrol.MediaEvent;
import javax.media.mscontrol.mediagroup.signals.SignalDetector;

/// One window of a party's voice, scored for being synthetic. See [MediaCallflow#assessVoice].
///
/// The score is a measurement, not a verdict. A single window says little: a real voice over a
/// poor line can score high once, and a cloned voice can score low while it is silent. A reader
/// that decides anything folds a run of windows together with what else it knows about the call.
public interface VoiceAssessmentEvent extends MediaEvent<SignalDetector> {

	/// The event types an assessment fires, in the same shape as [TranscriberEvent.Type].
	enum Type implements EventType {
		/// One window was scored. [#getScore] carries it.
		ASSESSED
	}

	/// The URI of the leg whose audio was scored. The application maps it to a party.
	String getParty();

	/// The probability the window's voice is synthetic: 0 genuine, 1 synthetic.
	double getScore();

	/// The scorer's name for its model, or null when it gives none.
	String getModel();

	/// Where the window ended, in milliseconds from when the assessment attached to the party.
	long getOffsetMillis();
}
