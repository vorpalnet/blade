package org.vorpal.blade.applications.console.tuning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.io.VersionedFileStore;

/// A point-in-time copy of every target's ServerStart: the answer to "what were the settings
/// before I touched them?"
///
/// Two snapshots matter. The **baseline** is the state install.sh left behind, captured once
/// when this app first sees the domain and rewritten only by an explicit re-baseline. The
/// **history** is the state immediately before each write this app makes; the framework's
/// [VersionedFileStore] keeps the last twenty. Either can be written back to a target verbatim
/// (see `JvmSettings` restore), which is the recovery path that used to mean hand-editing
/// config.xml with the AdminServer down.
///
/// The baseline is captured at first sight rather than reproduced from install.sh's argument
/// strings on purpose: a second copy of those strings would drift, and "what the domain
/// actually had" is the fact the operator wants, hand edits included.
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerStartSnapshot {

	/// One target's ServerStart as it was.
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Entry {
		protected String kind = "";
		protected String classPath = "";
		protected String arguments = "";
		protected String javaHome = "";
		protected String javaVendor = "";

		public Entry() {
		}

		public Entry(String kind, String classPath, String arguments, String javaHome, String javaVendor) {
			this.kind = kind;
			this.classPath = nz(classPath);
			this.arguments = nz(arguments);
			this.javaHome = nz(javaHome);
			this.javaVendor = nz(javaVendor);
		}

		@JsonPropertyDescription("server (a static server such as the AdminServer or engine0) or template (a server template governing dynamic engines).")
		public String getKind() {
			return kind;
		}

		public void setKind(String kind) {
			this.kind = kind;
		}

		@JsonPropertyDescription("ServerStart.ClassPath as it was: the jars Node Manager puts on the java line. Without the SIP jars a server boots with no SIP container.")
		public String getClassPath() {
			return classPath;
		}

		public void setClassPath(String classPath) {
			this.classPath = nz(classPath);
		}

		@JsonPropertyDescription("ServerStart.Arguments as it was: the complete JVM argument line.")
		public String getArguments() {
			return arguments;
		}

		public void setArguments(String arguments) {
			this.arguments = nz(arguments);
		}

		@JsonPropertyDescription("ServerStart.JavaHome as it was; empty means Node Manager's default JDK.")
		public String getJavaHome() {
			return javaHome;
		}

		public void setJavaHome(String javaHome) {
			this.javaHome = nz(javaHome);
		}

		@JsonPropertyDescription("ServerStart.JavaVendor as it was.")
		public String getJavaVendor() {
			return javaVendor;
		}

		public void setJavaVendor(String javaVendor) {
			this.javaVendor = nz(javaVendor);
		}
	}

	protected long capturedAtMillis;
	protected String capturedAt = "";
	protected String reason = "";
	protected Map<String, Entry> targets = new LinkedHashMap<>();

	public ServerStartSnapshot() {
	}

	/// Snapshot the given targets now.
	static ServerStartSnapshot capture(List<ServerStartTargets.Target> live, String reason) {
		ServerStartSnapshot s = new ServerStartSnapshot();
		s.capturedAtMillis = System.currentTimeMillis();
		s.capturedAt = Instant.ofEpochMilli(s.capturedAtMillis).toString();
		s.reason = nz(reason);
		for (ServerStartTargets.Target t : live) {
			s.targets.put(t.name, new Entry(t.kindName(), t.classPath, t.arguments, t.javaHome, t.javaVendor));
		}
		return s;
	}

	@JsonPropertyDescription("Capture time, epoch milliseconds. Also the id a history entry is fetched and restored by.")
	public long getCapturedAtMillis() {
		return capturedAtMillis;
	}

	public void setCapturedAtMillis(long capturedAtMillis) {
		this.capturedAtMillis = capturedAtMillis;
	}

	@JsonPropertyDescription("Capture time, ISO-8601 UTC, for reading.")
	public String getCapturedAt() {
		return capturedAt;
	}

	public void setCapturedAt(String capturedAt) {
		this.capturedAt = nz(capturedAt);
	}

	@JsonPropertyDescription("Why it was taken: first sight of the domain, before an apply, before a restore, or an operator re-baseline.")
	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = nz(reason);
	}

	@JsonPropertyDescription("Each target's ServerStart, keyed by the server or template name.")
	public Map<String, Entry> getTargets() {
		return targets;
	}

	public void setTargets(Map<String, Entry> targets) {
		this.targets = targets == null ? new LinkedHashMap<>() : targets;
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	/// The two snapshot files under the app's config directory, and the history behind them.
	///
	/// `blade-tuning-baseline.json` is written by [#writeBaseline] only. `blade-tuning-history.json`
	/// is rewritten by [#recordHistory] before every write; [VersionedFileStore] moves the previous
	/// content into `.versions/` first, so the live file plus its versions are the last
	/// `DEFAULT_MAX_VERSIONS + 1` pre-write states. History entries are identified by their own
	/// `capturedAtMillis`, not by the backup file's timestamp, so an entry keeps its id whether it
	/// is still the live file or has rolled into `.versions/`.
	static final class Store {
		static final String BASELINE_FILE = "blade-tuning-baseline.json";
		static final String HISTORY_FILE = "blade-tuning-history.json";

		private static final ObjectMapper MAPPER = new ObjectMapper();
		private final VersionedFileStore files = new VersionedFileStore();
		private final Path dir;

		/// @param dir the config directory, normally `config/custom/vorpal` relative to DOMAIN_HOME
		Store(Path dir) {
			this.dir = dir;
		}

		Path baselineFile() {
			return dir.resolve(BASELINE_FILE);
		}

		Path historyFile() {
			return dir.resolve(HISTORY_FILE);
		}

		boolean hasBaseline() {
			return Files.exists(baselineFile());
		}

		/// The pinned baseline, or null when none has been captured yet.
		ServerStartSnapshot readBaseline() throws IOException {
			if (!hasBaseline()) return null;
			return MAPPER.readValue(files.read(baselineFile()), ServerStartSnapshot.class);
		}

		/// Pin a baseline. Any previous baseline goes into `.versions/` beside it.
		void writeBaseline(ServerStartSnapshot s) throws IOException {
			files.write(baselineFile(), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(s));
		}

		/// Record the state a write is about to replace.
		void recordHistory(ServerStartSnapshot s) throws IOException {
			files.write(historyFile(), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(s));
		}

		/// Every retained history snapshot, newest first.
		List<ServerStartSnapshot> listHistory() throws IOException {
			List<ServerStartSnapshot> out = new ArrayList<>();
			Path live = historyFile();
			if (Files.exists(live)) {
				out.add(MAPPER.readValue(files.read(live), ServerStartSnapshot.class));
			}
			for (VersionedFileStore.VersionInfo v : files.listVersions(live)) {
				out.add(MAPPER.readValue(files.readVersion(live, v.getTimestamp()), ServerStartSnapshot.class));
			}
			out.sort((a, b) -> Long.compare(b.capturedAtMillis, a.capturedAtMillis));
			return out;
		}

		/// The history snapshot with this `capturedAtMillis`, or null.
		ServerStartSnapshot readHistory(long capturedAtMillis) throws IOException {
			for (ServerStartSnapshot s : listHistory()) {
				if (s.capturedAtMillis == capturedAtMillis) return s;
			}
			return null;
		}
	}
}
