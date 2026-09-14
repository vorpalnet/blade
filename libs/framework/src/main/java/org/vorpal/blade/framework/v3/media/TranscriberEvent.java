package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.EventType;
import javax.media.mscontrol.MediaEvent;
import javax.media.mscontrol.mediagroup.signals.SignalDetector;

import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// One thing a party said, as a transcription heard it. See [MediaCallflow#transcribe].
///
/// The words, their bounds and what produced them travel in the [Utterance], which is the stored
/// form. Its `party` is the URI of the leg whose audio it was, because the driver knows the endpoint
/// and not the person: an application that knows which connection is the caller labels the utterance
/// before storing it.
public interface TranscriberEvent extends MediaEvent<SignalDetector> {

	/// The event types a transcription fires. JSR-309's `EventType` is a marker interface, and the
	/// specification's own constants are an enum behind it, so this follows the same shape.
	enum Type implements EventType {
		/// One utterance finished. [#getUtterance] carries it.
		UTTERANCE
	}

	/// What was said, with its bounds, its provenance, and the URI of the party who said it.
	Utterance getUtterance();
}
