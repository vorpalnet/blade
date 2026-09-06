package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.mediagroup.Recorder;

/// A [Recorder] that can stop writing without closing the recording.
///
/// ## Why this interface exists
///
/// JSR-309's `Recorder` has `record` and `stop` and nothing between them. Stop
/// closes the recording, so using it to skip a passage would end the recording
/// and any resumption would be a different file. There is no way in the
/// specification to leave a gap in one recording.
///
/// Two requirements need exactly that gap, and both are about content that must
/// never reach storage rather than content nobody wants to hear:
///
/// - **Hold.** A caller on hold is not having a conversation, and the hold music
///   is not part of the record.
/// - **A PCI pause.** A card number read aloud must not be written down. Pausing
///   the recorder means the digits never reach the muxer, so they are not in the
///   file to be found later. Redacting afterwards is a weaker guarantee, because
///   it depends on the redaction running.
///
/// ## Why an interface rather than a driver call
///
/// A BLADE application programs `javax.media.mscontrol` and never a vendor. A
/// driver whose recorder can pause implements this, and the framework asks
/// through [MediaCallflow#pauseRecording]; a driver whose recorder cannot is
/// still a valid JSR-309 driver, and the application still compiles and runs.
/// The capability is discovered rather than assumed.
///
/// A deployment whose driver does not implement this cannot honour a pause. That
/// is a fact worth surfacing loudly rather than papering over, because the
/// failure is silent otherwise: the recording simply contains the passage that
/// was supposed to be left out.
public interface PausableRecorder extends Recorder {

	/// Stop writing, keeping the recording open. The paused span never reaches
	/// the muxer.
	void pauseRecording() throws MsControlException;

	/// Resume writing into the same recording.
	void resumeRecording() throws MsControlException;
}
