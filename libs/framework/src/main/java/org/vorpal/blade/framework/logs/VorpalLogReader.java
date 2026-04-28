package org.vorpal.blade.framework.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class VorpalLogReader implements VorpalLogReaderMXBean {

	/// Hard cap so a single JMX call can't move tens of megabytes.
	static final int MAX_BYTES_PER_CALL = 1 << 20; // 1 MiB

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
