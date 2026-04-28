package org.vorpal.blade.framework.logs;

import java.beans.ConstructorProperties;

/// A chunk of log file bytes plus the cursor needed to fetch the next chunk.
/// JMX MXBean value type — see [LogFileInfo] for the rationale on
/// `@ConstructorProperties`.
public class LogSlice {

	private final byte[] bytes;
	private final long newOffset;
	private final boolean eofReached;
	private final boolean truncatedAtStart;

	@ConstructorProperties({ "bytes", "newOffset", "eofReached", "truncatedAtStart" })
	public LogSlice(byte[] bytes, long newOffset, boolean eofReached, boolean truncatedAtStart) {
		this.bytes = bytes;
		this.newOffset = newOffset;
		this.eofReached = eofReached;
		this.truncatedAtStart = truncatedAtStart;
	}

	public byte[] getBytes() { return bytes; }
	public long getNewOffset() { return newOffset; }
	public boolean isEofReached() { return eofReached; }
	public boolean isTruncatedAtStart() { return truncatedAtStart; }
}
