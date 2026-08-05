package org.vorpal.blade.framework.v2.logging;

import java.beans.ConstructorProperties;

/// The outcome of one bounded pass of [VorpalLogReaderMXBean#search].
///
/// A pass stops at whichever limit it reaches first — matches found or bytes
/// scanned — so a search over a very large file is a sequence of calls rather
/// than one long one. `complete` says whether the file ran out; if it did not,
/// `nextOffset` is where the following pass should resume.
///
/// JMX MXBean value type — see [LogFileInfo] for the rationale on
/// `@ConstructorProperties`.
public class LogSearchResult {

	private final LogMatch[] matches;
	private final long nextOffset;
	private final boolean complete;
	private final long bytesScanned;

	@ConstructorProperties({ "matches", "nextOffset", "complete", "bytesScanned" })
	public LogSearchResult(LogMatch[] matches, long nextOffset, boolean complete, long bytesScanned) {
		this.matches = matches;
		this.nextOffset = nextOffset;
		this.complete = complete;
		this.bytesScanned = bytesScanned;
	}

	public LogMatch[] getMatches() { return matches; }

	/// Where a following pass should resume. Meaningless when `complete`.
	public long getNextOffset() { return nextOffset; }

	/// True when the scan reached the end of the file.
	public boolean isComplete() { return complete; }

	/// How much of the file this pass actually read — what the caller shows an
	/// operator who wants to know why a search stopped early.
	public long getBytesScanned() { return bytesScanned; }
}
