package org.vorpal.blade.framework.v2.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class VorpalLogReader implements VorpalLogReaderMXBean {

	/// Hard cap so a single JMX call can't move tens of megabytes.
	static final int MAX_BYTES_PER_CALL = 1 << 20; // 1 MiB

	/// Ceilings for one search pass. The caller asks for less; it never gets
	/// more. This runs on an engine node carrying calls, so the point is that
	/// no single request from a console can occupy it for an unbounded time.
	static final int MAX_MATCHES_PER_CALL = 1000;
	static final long MAX_SCAN_PER_CALL = 64L << 20; // 64 MiB
	private static final int SCAN_BUFFER = 256 * 1024;

	/// A match is shown in a list, not read in full, so a very long line is
	/// truncated rather than sent whole through JMX.
	private static final int MAX_MATCH_TEXT = 1000;

	/// Regex backtracking cost grows with input length, so bounding the input
	/// bounds the damage a bad pattern can do. Literal search — the default the
	/// viewer offers — has no such exposure and is not limited.
	private static final int MAX_REGEX_LINE = 8192;

	/// A stray binary blob in a log must not grow one "line" without limit.
	private static final int MAX_LINE_BYTES = 1 << 20;

	private final Path logsRoot;
	private final String serverName;

	public VorpalLogReader(String serverName, Path logsRoot) {
		this.serverName = serverName;
		this.logsRoot = logsRoot.toAbsolutePath().normalize();
	}

	@Override
	public LogFileInfo[] listLogFiles() {
		List<LogFileInfo> out = new ArrayList<>();
		collectTopLevel(out);
		collectVorpalSubdir(out);
		return out.toArray(new LogFileInfo[0]);
	}

	private void collectTopLevel(List<LogFileInfo> out) {
		if (!Files.isDirectory(logsRoot)) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(logsRoot)) {
			for (Path p : ds) {
				if (!Files.isRegularFile(p)) continue;
				String name = p.getFileName().toString();
				String kind = classifyTopLevel(name);
				if (kind == null) continue;
				out.add(toInfo(name, p, kind));
			}
		} catch (IOException ignored) {
		}
	}

	private void collectVorpalSubdir(List<LogFileInfo> out) {
		Path vorpalDir = logsRoot.resolve("vorpal");
		if (!Files.isDirectory(vorpalDir)) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(vorpalDir)) {
			for (Path p : ds) {
				if (!Files.isRegularFile(p)) continue;
				String name = p.getFileName().toString();
				if (name.endsWith(".lck")) continue;
				out.add(toInfo("vorpal/" + name, p, LogFileInfo.KIND_VORPAL_APP));
			}
		} catch (IOException ignored) {
		}
	}

	private String classifyTopLevel(String name) {
		if (name.endsWith(".lck")) return null;
		if (name.equals(serverName + ".log") || name.startsWith(serverName + ".log")) {
			return LogFileInfo.KIND_WLS_SERVER;
		}
		if (name.startsWith("access.log")) {
			return LogFileInfo.KIND_WLS_ACCESS;
		}
		if (name.endsWith(".log") || name.endsWith(".out")) {
			return LogFileInfo.KIND_OTHER;
		}
		return null;
	}

	private LogFileInfo toInfo(String relativePath, Path p, String kind) {
		long size = 0L;
		long mtime = 0L;
		try {
			size = Files.size(p);
			mtime = Files.getLastModifiedTime(p).toMillis();
		} catch (IOException ignored) {
		}
		return new LogFileInfo(relativePath, size, mtime, kind);
	}

	@Override
	public LogSlice readSlice(String relativePath, long offset, int maxBytes) {
		Path target = resolveSafe(relativePath);
		int cap = Math.min(Math.max(maxBytes, 0), MAX_BYTES_PER_CALL);
		try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "r")) {
			long len = raf.length();
			long start = (offset < 0) ? Math.max(0L, len - cap) : Math.min(offset, len);
			raf.seek(start);
			int toRead = (int) Math.min(cap, len - start);
			byte[] buf = new byte[toRead];
			raf.readFully(buf);
			long newOffset = start + toRead;
			return new LogSlice(buf, newOffset, newOffset >= len, false);
		} catch (IOException e) {
			return new LogSlice(new byte[0], offset < 0 ? 0L : offset, true, false);
		}
	}

	@Override
	public LogSlice tail(String relativePath, long cursor, int maxBytes) {
		Path target = resolveSafe(relativePath);
		int cap = Math.min(Math.max(maxBytes, 0), MAX_BYTES_PER_CALL);
		try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "r")) {
			long len = raf.length();
			boolean truncated = false;
			long start = cursor;
			if (cursor > len) {
				// File rotated / truncated — restart from the new beginning.
				start = 0L;
				truncated = true;
			}
			long available = len - start;
			int toRead = (int) Math.min(cap, available);
			raf.seek(start);
			byte[] buf = new byte[Math.max(toRead, 0)];
			if (toRead > 0) raf.readFully(buf);
			long newOffset = start + Math.max(toRead, 0);
			return new LogSlice(buf, newOffset, newOffset >= len, truncated);
		} catch (IOException e) {
			return new LogSlice(new byte[0], cursor, true, false);
		}
	}

	@Override
	public LogSearchResult search(String relativePath, String pattern, boolean regex,
			boolean ignoreCase, long fromOffset, int maxMatches, long maxBytesScanned) {

		Path target = resolveSafe(relativePath);
		if (pattern == null || pattern.isEmpty()) {
			throw new IllegalArgumentException("pattern required");
		}

		int matchCap = Math.min(maxMatches <= 0 ? MAX_MATCHES_PER_CALL : maxMatches, MAX_MATCHES_PER_CALL);
		long scanCap = Math.min(maxBytesScanned <= 0 ? MAX_SCAN_PER_CALL : maxBytesScanned, MAX_SCAN_PER_CALL);

		Pattern re = null;
		if (regex) {
			try {
				re = Pattern.compile(pattern,
						ignoreCase ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0);
			} catch (PatternSyntaxException e) {
				// Surface the operator's own mistake as its own message rather
				// than a stack trace from inside the regex engine.
				throw new IllegalArgumentException("invalid regular expression: " + e.getDescription());
			}
		}
		String needle = ignoreCase ? pattern.toLowerCase() : pattern;

		List<LogMatch> found = new ArrayList<>();
		long lineStart = 0L;
		long scanned = 0L;
		boolean complete = false;

		try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "r")) {
			long len = raf.length();
			long pos = Math.max(0L, Math.min(fromOffset, len));
			lineStart = pos;
			raf.seek(pos);

			byte[] buf = new byte[SCAN_BUFFER];
			ByteArrayOutputStream line = new ByteArrayOutputStream(256);
			boolean overlong = false;

			scan:
			while (scanned < scanCap && found.size() < matchCap) {
				int want = (int) Math.min(buf.length, Math.min(scanCap - scanned, len - pos));
				if (want <= 0) {
					complete = (pos >= len);
					break;
				}
				int n = raf.read(buf, 0, want);
				if (n <= 0) {
					complete = true;
					break;
				}

				for (int i = 0; i < n; i++) {
					if (buf[i] != '\n') {
						if (line.size() < MAX_LINE_BYTES) line.write(buf[i]);
						else overlong = true;
						continue;
					}
					String text = decodeLine(line, overlong);
					if (lineMatches(text, re, needle, ignoreCase)) {
						found.add(new LogMatch(lineStart, truncate(text)));
					}
					line.reset();
					overlong = false;
					lineStart = pos + i + 1;
					if (found.size() >= matchCap) {
						scanned += (i + 1);
						break scan;
					}
				}

				pos += n;
				scanned += n;
				if (pos >= len) {
					// A file whose last line has no terminating newline still
					// has that line; it is only unfinished if more bytes are
					// coming, and at EOF none are.
					if (line.size() > 0) {
						String text = decodeLine(line, overlong);
						if (lineMatches(text, re, needle, ignoreCase)) {
							found.add(new LogMatch(lineStart, truncate(text)));
						}
						lineStart = pos;
					}
					complete = true;
					break;
				}
			}
		} catch (IOException e) {
			// Consistent with readSlice and tail: an unreadable file yields an
			// empty answer rather than an exception through the JMX proxy.
			return new LogSearchResult(new LogMatch[0], fromOffset, true, 0L);
		}

		return new LogSearchResult(found.toArray(new LogMatch[0]), lineStart, complete, scanned);
	}

	private static String decodeLine(ByteArrayOutputStream line, boolean overlong) {
		String s = new String(line.toByteArray(), StandardCharsets.UTF_8);
		if (!s.isEmpty() && s.charAt(s.length() - 1) == '\r') {
			s = s.substring(0, s.length() - 1);
		}
		return overlong ? s + " …[line truncated]" : s;
	}

	private static boolean lineMatches(String text, Pattern re, String needle, boolean ignoreCase) {
		if (re != null) {
			String probe = text.length() > MAX_REGEX_LINE ? text.substring(0, MAX_REGEX_LINE) : text;
			return re.matcher(probe).find();
		}
		return (ignoreCase ? text.toLowerCase() : text).contains(needle);
	}

	private static String truncate(String text) {
		return text.length() <= MAX_MATCH_TEXT ? text : text.substring(0, MAX_MATCH_TEXT) + " …";
	}

	private Path resolveSafe(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			throw new IllegalArgumentException("relativePath required");
		}
		if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.contains("..")) {
			throw new IllegalArgumentException("invalid relativePath: " + relativePath);
		}
		Path p = logsRoot.resolve(relativePath).normalize();
		if (!p.startsWith(logsRoot)) {
			throw new IllegalArgumentException("relativePath escapes logs root: " + relativePath);
		}
		return p;
	}

	Path getLogsRoot() {
		return logsRoot;
	}

	public static Path defaultLogsRoot(String serverName) {
		// WebLogic managed servers run with user.dir = $DOMAIN_HOME, so logs
		// live at servers/<ServerName>/logs/. SettingsManager already relies on
		// this convention (see CONFIG_BASE_PATH = "config/custom/vorpal/").
		return Paths.get("servers", serverName, "logs");
	}
}
