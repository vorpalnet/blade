package org.vorpal.blade.framework.v2.logging;

import java.beans.ConstructorProperties;

/// One matching line, with the byte offset that locates it in the file.
///
/// The offset is what makes search useful rather than merely informative: the
/// viewer navigates by byte offset, so a match is somewhere it can jump to, not
/// just text to read in a separate list.
///
/// JMX MXBean value type — see [LogFileInfo] for the rationale on
/// `@ConstructorProperties`.
public class LogMatch {

	private final long offset;
	private final String text;

	@ConstructorProperties({ "offset", "text" })
	public LogMatch(long offset, String text) {
		this.offset = offset;
		this.text = text;
	}

	/// Byte offset of the first character of the matching line.
	public long getOffset() { return offset; }

	/// The matching line, truncated by the reader if it was very long.
	public String getText() { return text; }
}
