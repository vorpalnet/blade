package org.vorpal.blade.framework.v2.logging;

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

	/// Scan `relativePath` for lines matching `pattern`, starting at
	/// `fromOffset`.
	///
	/// The scan runs on the node that owns the bytes, so only the matches cross
	/// the wire. Reading a large file back to the AdminServer to grep it there
	/// would move the whole file per search.
	///
	/// Bounded on both axes — it stops at `maxMatches` or `maxBytesScanned`,
	/// whichever comes first — because the caller is an operator at a console
	/// and the file may be gigabytes. Resume with the returned `nextOffset`.
	///
	/// When `regex` is false the pattern is a literal substring, which is the
	/// default the viewer offers: a regex here is supplied by a human and runs
	/// on a production engine node, where a pathological pattern would burn CPU
	/// that is carrying calls. Implementations must bound that exposure.
	///
	/// ADDING methods to this interface is safe; changing existing signatures is
	/// not. Note also that a node keeps the reader registered by the first BLADE
	/// WAR to start in that JVM, so a node that has not restarted since this
	/// method was added will not have it — callers must probe with
	/// `MBeanServer.getMBeanInfo` rather than assume.
	LogSearchResult search(String relativePath, String pattern, boolean regex,
			boolean ignoreCase, long fromOffset, int maxMatches, long maxBytesScanned);
}
