package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.EventType;
import javax.media.mscontrol.MediaEvent;
import javax.media.mscontrol.mediagroup.signals.SignalDetector;

import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// One thing a party said, as a transcription heard it. See [MediaCallflow#transcribe].
///
/// An utterance can arrive twice. [Type#LIVE_UTTERANCE] is the first decode, delivered as soon as
/// the party stops speaking, for a screen or a rule that must act while the call is live.
/// [Type#UTTERANCE] is the decode for the record, which may come seconds later from a larger model
/// and is the one to store. A live utterance's bounds can be approximate, because a first decode
/// may carry no media clock, so a reader pairs the two by party and order rather than by bounds.
/// A driver with a single decode delivers only [Type#UTTERANCE].
///
/// The words, their bounds and what produced them travel in the [Utterance], which is the stored
/// form. Its `party` is the URI of the leg whose audio it was, because the driver knows the endpoint
/// and not the person: an application that knows which connection is the caller labels the utterance
/// before storing it.
public interface TranscriberEvent extends MediaEvent<SignalDetector> {

	/// The event types a transcription fires. JSR-309's `EventType` is a marker interface, and the
	/// specification's own constants are an enum behind it, so this follows the same shape.
	enum Type implements EventType {
		/// One utterance finished, decoded for the record. [#getUtterance] carries it.
		UTTERANCE,
		/// One utterance finished, as first decoded. Never stored: the same words arrive again as
		/// [#UTTERANCE], possibly corrected.
		LIVE_UTTERANCE,
		/// A party started speaking, a fraction of a second in, long before any words are decoded.
		/// [#getUtterance] carries only its party; there is no text. Delivered only to a listener that
		/// asks for it ([MediaCallflow#transcribe(javax.media.mscontrol.mediagroup.MediaGroup,
		/// javax.media.mscontrol.MediaEventListener, boolean)]), so a listener that stores every event
		/// it receives never stores an empty utterance.
		SPEECH_STARTED
	}

	/// What was said, with its bounds, its provenance, and the URI of the party who said it.
	Utterance getUtterance();
}
