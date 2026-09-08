package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.MediaEventNotifier;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.resource.Resource;

/// Hears what each party says on the source a [MediaGroup] is joined to, and
/// reports it as text.
///
/// ## Why this interface exists
///
/// JSR-309 has a recorder and a DTMF detector and nothing that turns speech into
/// words. A BLADE application programs `javax.media.mscontrol` and never a
/// vendor, so a driver whose media server can transcribe implements this and the
/// application finds it with `ResourceContainer.getResource(Transcriber.class)`,
/// the specification's own door for a resource it did not define. A driver
/// whose media server cannot is still a valid driver: `getResource` returns
/// null, [MediaCallflow#transcribe] returns false, and the call proceeds with
/// audio only.
///
/// ## One party at a time
///
/// A group joined to a mixer hears the mix, and a transcript drawn from a mix
/// has to guess who spoke. This resource does not transcribe what the group
/// hears. It transcribes each party the group's source carries, separately, so
/// every utterance arrives knowing whose it was. That is
/// [org.vorpal.blade.framework.v3.media.manifest.TranscriptRef.Attribution#PER_TRACK]
/// attribution even when the audio itself is stored as one mixed track, and it
/// costs nothing, because the media server already has each party on its own
/// endpoint.
///
/// ## Offsets share the recording's clock
///
/// Every utterance carries where it started and ended, measured from the moment
/// [#start] was called. An application that starts the transcriber alongside
/// the recorder therefore gets offsets on the recording's own timeline, and a
/// line of transcript and the audio behind it are the same number.
///
/// ## Pause follows the recorder
///
/// A PCI pause keeps a card number out of the muxer. A transcriber that kept
/// listening through it would write the same number down in the clear, so
/// [MediaCallflow#pauseRecording] pauses this too. What was said during a pause
/// is never heard and never written, and the clock keeps running, so offsets
/// after the pause remain true.
///
/// ## The listener is node-local
///
/// Events reach the application on a driver thread, through a listener that
/// lives on this node. It does not survive a failover, and does not need to:
/// every utterance an application has been given is already durable if the
/// application stored it, and the transcript's own record of what it covers is
/// the manifest, not this object.
public interface Transcriber extends Resource<MediaGroup>, MediaEventNotifier<TranscriberEvent> {

	/// Begin hearing every party on the group's joined source. Utterances arrive
	/// as [TranscriberEvent]s until [#stop].
	void start() throws MsControlException;

	/// Stop hearing without stopping. Nothing said until [#resume] is
	/// transcribed, and the clock keeps running. What was heard before the
	/// pause and not yet delivered may still arrive after it.
	void pause() throws MsControlException;

	/// Hear again, on the same clock.
	void resume() throws MsControlException;

	/// Stop for good and release what was listening. Safe to call more than once.
	void stop();
}
