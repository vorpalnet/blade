package org.vorpal.blade.services.listener;

/// What an INVITE means for the recording of the call it belongs to.
public enum RecordingAction {

	/// A new conversation begins here. Stop and release whatever was recording,
	/// then start a fresh recording with its own identity and its own
	/// classification.
	START_CONVERSATION,

	/// Stop writing, without closing the recording. The span never reaches the
	/// muxer, so it is not in the file to be found later.
	PAUSE,

	/// Resume writing into the same recording.
	RESUME,

	/// Nothing to do. The commonest answer, and the one a session refresh gets.
	NONE
}
