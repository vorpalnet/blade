package org.vorpal.blade.framework.v3.tester;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Loads and caches [SipMessageTemplate]s from
/// `config/custom/vorpal/_templates/`. The cache is keyed by filename and
/// invalidated by file mtime, so an operator edit is picked up on the next
/// call — no servlet bounce, no manual cache flush.
///
/// One instance per servlet/engine — never a singleton; each node reads its
/// own files.
public class TemplateLoader {

	public static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	private static class Entry {
		final long mtime;
		final SipMessageTemplate template;

		Entry(long mtime, SipMessageTemplate template) {
			this.mtime = mtime;
			this.template = template;
		}
	}

	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	/// Returns the parsed template, re-reading the file when its mtime has
	/// changed. Throws [IOException] when the file doesn't exist.
	public SipMessageTemplate get(String filename) throws IOException {
		Path p = Paths.get(TEMPLATES_DIR, filename);
		if (!Files.exists(p)) {
			throw new IOException("Template not found: " + p);
		}
		long mtime = Files.getLastModifiedTime(p).toMillis();

		Entry e = cache.get(filename);
		if (e == null || e.mtime != mtime) {
			e = new Entry(mtime, SipMessageTemplate.parse(Files.readString(p)));
			cache.put(filename, e);
		}
		return e.template;
	}
}
