package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.vorpal.blade.framework.v3.configuration.templates.TemplatesControl;
import org.vorpal.blade.framework.v3.configuration.templates.TemplatesMXBean;

/// Pushes `_templates/` files to every server, as configuration files are
/// pushed: on a save while auto-publish is on (the watcher), and on Publish.
///
/// One `Templates` MBean per server is used (any application's: a server's
/// applications share the folder). A server already holding the same bytes is
/// skipped, which is also how a shared disk is recognised: the push never
/// rewrites the file the admin just wrote. With `blade.shared.filesystem`
/// declared true nothing is pushed. A server running BLADE applications but no
/// Templates MBean runs a framework from before template pushes; it is
/// reported, not silently skipped.
final class TemplatePush {

	private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

	/// What a push did, by server.
	static final class Result {
		final List<String> pushed = new ArrayList<>();
		final List<String> same = new ArrayList<>();
		final List<String> failed = new ArrayList<>();
		final List<String> noReceiver = new ArrayList<>();
		boolean sharedDeclared;

		/// One line for the page and the log.
		String summary() {
			if (sharedDeclared) return "the engines share this disk (declared): nothing to push";
			StringBuilder b = new StringBuilder();
			if (!pushed.isEmpty()) b.append("pushed to ").append(String.join(", ", pushed));
			if (!same.isEmpty()) b.append(b.length() > 0 ? "; " : "").append("already current on ").append(String.join(", ", same));
			if (!failed.isEmpty()) b.append(b.length() > 0 ? "; " : "").append("FAILED on ").append(String.join(", ", failed));
			if (!noReceiver.isEmpty()) {
				b.append(b.length() > 0 ? "; " : "").append("not sent to ").append(String.join(", ", noReceiver))
						.append(" (its applications predate template pushes: redeploy them)");
			}
			return b.length() == 0 ? "no servers run BLADE applications" : b.toString();
		}
	}

	private TemplatePush() {
	}

	/// The admin's `_templates/` folder.
	static Path base() {
		return Paths.get(FileManagerServlet.templatesDir()).toAbsolutePath().normalize();
	}

	/// Pushes template `path` (relative to `_templates/`) to every server.
	static Result push(MBeanServer mbs, String path) throws Exception {
		Result r = new Result();
		if (Boolean.TRUE.equals(ConfigurationMonitor.explicitSharedFileSystem())) {
			r.sharedDeclared = true;
			return r;
		}
		Path file = base().resolve(path).normalize();
		if (!file.startsWith(base()) || !Files.isRegularFile(file)) throw new IOException("no such template: " + path);
		synchronized (LOCKS.computeIfAbsent(path, k -> new Object())) {
			byte[] bytes = Files.readAllBytes(file);
			String sum = TemplatesControl.checksum(bytes);
			// Sent as text: bytes that are not UTF-8 would arrive as U+FFFD, and
			// the copy would never match (pushed again on every Publish).
			String content;
			try {
				content = StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
						.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
						.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
			} catch (java.nio.charset.CharacterCodingException e) {
				throw new IOException(path + " is not UTF-8 text, so it cannot be pushed");
			}
			for (Map.Entry<String, TemplatesMXBean> e : receivers(mbs, r).entrySet()) {
				try {
					if (sum.equals(e.getValue().checksum(path))) {
						r.same.add(e.getKey());
					} else {
						e.getValue().write(path, content);
						r.pushed.add(e.getKey());
					}
				} catch (Exception failure) {
					r.failed.add(e.getKey() + " (" + failure.getMessage() + ")");
				}
			}
		}
		return r;
	}

	/// What a push of every template did: one line (null when there is nothing
	/// to say) and whether any server failed, which keeps the templates pending.
	static final class Summary {
		final String line;
		final boolean failed;

		Summary(String line, boolean failed) {
			this.line = line;
			this.failed = failed;
		}
	}

	/// Pushes every template (Publish): what changed, and the servers that
	/// cannot receive templates.
	static Summary pushAll(MBeanServer mbs) throws Exception {
		if (Boolean.TRUE.equals(ConfigurationMonitor.explicitSharedFileSystem()) || !Files.isDirectory(base())) return new Summary(null, false);
		boolean failed = false;
		List<String> lines = new ArrayList<>();
		TreeSet<String> noReceiver = new TreeSet<>();
		try (Stream<Path> files = Files.walk(base())) {
			for (Path f : (Iterable<Path>) files.filter(TemplatePush::isTemplate)::iterator) {
				String path = base().relativize(f).toString().replace('\\', '/');
				Result r;
				try {
					r = push(mbs, path);
				} catch (IOException unpushable) {
					// One template that cannot be pushed does not stop the rest.
					lines.add(unpushable.getMessage());
					failed = true;
					continue;
				}
				noReceiver.addAll(r.noReceiver);
				failed |= !r.failed.isEmpty();
				r.noReceiver.clear();   // said once, below
				if (!r.pushed.isEmpty() || !r.failed.isEmpty()) lines.add(path + ": " + r.summary());
			}
		}
		// Every template misses these servers: one line, not one per file.
		if (!noReceiver.isEmpty()) {
			lines.add("not sent to " + String.join(", ", noReceiver) + " (its applications predate template pushes: redeploy them)");
		}
		return new Summary(lines.isEmpty() ? null : String.join("; ", lines), failed);
	}

	/// A template file: not a backup in `.versions/`, not a temp file being written.
	static boolean isTemplate(Path f) {
		if (!Files.isRegularFile(f)) return false;
		Path rel = base().relativize(f.toAbsolutePath().normalize());
		for (Path part : rel) {
			if (part.toString().startsWith(".")) return false;
		}
		return !f.getFileName().toString().endsWith(".tmp");
	}

	/// One Templates MBean per server (`Location`), by server name; servers
	/// with BLADE applications but none are added to `r.noReceiver`.
	private static Map<String, TemplatesMXBean> receivers(MBeanServer mbs, Result r) throws Exception {
		Map<String, TemplatesMXBean> out = new LinkedHashMap<>();
		for (ObjectInstance i : mbs.queryMBeans(new ObjectName("vorpal.blade:Type=Templates,*"), null)) {
			String server = i.getObjectName().getKeyProperty("Location");
			if (server != null && !out.containsKey(server)) {
				out.put(server, JMX.newMXBeanProxy(mbs, i.getObjectName(), TemplatesMXBean.class));
			}
		}
		TreeSet<String> withApps = new TreeSet<>();
		for (ObjectInstance i : mbs.queryMBeans(new ObjectName("vorpal.blade:Type=Configuration,*"), null)) {
			String server = i.getObjectName().getKeyProperty("Location");
			if (server != null) withApps.add(server);
		}
		withApps.removeAll(out.keySet());
		r.noReceiver.addAll(withApps);
		return out;
	}
}
