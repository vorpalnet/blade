package org.vorpal.blade.framework.logs;

/// JMX interface every BLADE JVM exposes so the admin/logs WAR running on
/// AdminServer can read this server's log files (engine.log, access.log,
/// vorpal/&lt;app&gt;.0.log, etc.) over the standard DomainRuntime channel —
/// no agents, no SSH, no shared filesystem.
public interface VorpalLogReaderMXBean {

	/// Catalog every log file this server can offer.
	LogFileInfo[] listLogFiles();

	/// Read up to `maxBytes` from `relativePath` starting at `offset`.
	/// `offset == -1` means "give me the last `maxBytes`". `relativePath` is
	/// relative to the server's `logs/` directory and must not escape it.
	LogSlice readSlice(String relativePath, long offset, int maxBytes);

	/// Tail since `cursor` — return new bytes appended since `cursor`, plus
	/// the updated cursor. If the file rotated under us, the slice's
	/// `truncatedAtStart` flag is set and the cursor is reset to the live file.
	LogSlice tail(String relativePath, long cursor, int maxBytes);
}
