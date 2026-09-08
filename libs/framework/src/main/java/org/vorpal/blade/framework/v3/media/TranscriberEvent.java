package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.EventType;
import javax.media.mscontrol.MediaEvent;
import javax.media.mscontrol.join.Joinable;

import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// One thing a party said, as a [Transcriber] heard it.
///
/// The words, their bounds and what produced them travel in the
/// [Utterance], which is the stored form. The party travels beside it as the
/// [Joinable] whose audio it was, because the driver knows the endpoint and not
/// the person: an application that knows which connection is the caller labels
/// the utterance before storing it.
public interface TranscriberEvent extends MediaEvent<Transcriber> {

	/// The event types a transcriber fires. JSR-309's `EventType` is a marker
	/// interface, and the specification's own constants are an enum behind it,
	/// so this follows the same shape.
	enum Type implements EventType {
		/// One utterance finished. [#getUtterance] carries it.
		UTTERANCE
	}

	/// What was said, with its bounds and provenance. The party field is the
	/// driver's name for the source; see [#getParty] for the object itself.
	Utterance getUtterance();

	/// The party whose audio this was, as the [javax.media.mscontrol.networkconnection.NetworkConnection]
	/// or other joinable carrying it.
	Joinable getParty();
}
