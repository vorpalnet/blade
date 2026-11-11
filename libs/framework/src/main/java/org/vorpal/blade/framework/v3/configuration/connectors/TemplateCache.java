package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// The text of each `_templates/` file a connector uses, read again when the
/// file changes: the Configurator pushes a saved template to every engine
/// (`TemplatesControl`), and a connector built before the push must use the
/// new text on its next call, without waiting for its application to reload.
///
/// A file is looked at (one `stat`, not a read) at most once a second per
/// path; it is read again only when its modification time, size or file key
/// changed. The file key matters: a push replaces the file atomically, so a
/// new template of the same length written within the same second (whole-
/// second times on NFS) still reads as changed.
///
/// The returned text is the same `String` instance until the file changes, so
/// a caller can keep what it derives from it (a compiled query) and rebuild
/// only when `read` hands back another instance.
public final class TemplateCache {

	/// How long a looked-at file is trusted before it is looked at again.
	static final long CHECK_MILLIS = 1000;

	private static final class Entry {
		final String text;
		final long modified;
		final long size;
		final Object fileKey;
		volatile long checked;

		Entry(String text, BasicFileAttributes a, long checked) {
			this.text = text;
			this.modified = a.lastModifiedTime().toMillis();
			this.size = a.size();
			this.fileKey = a.fileKey();
			this.checked = checked;
		}

		boolean same(BasicFileAttributes a) {
			return modified == a.lastModifiedTime().toMillis() && size == a.size() && Objects.equals(fileKey, a.fileKey());
		}
	}

	private static final ConcurrentHashMap<Path, Entry> CACHE = new ConcurrentHashMap<>();

	/// What a connector made from a template's text (a compiled query, a
	/// stripped body), with the text it was made from: one immutable pair,
	/// replaced in one write, so racing calls never pair new text with an old
	/// result. Rebuild when [#read] hands back other text than `raw`.
	public static final class Derived<T> {
		public final String raw;
		public final T value;

		public Derived(String raw, T value) {
			this.raw = raw;
			this.value = value;
		}
	}

	private TemplateCache() {
	}

	/// The file's text, or null when there is no such file.
	public static String read(Path file) throws IOException {
		Path key = file.toAbsolutePath().normalize();
		long now = System.currentTimeMillis();
		Entry e = CACHE.get(key);
		if (e != null && now - e.checked < CHECK_MILLIS) return e.text;
		if (!Files.isRegularFile(key)) {
			CACHE.remove(key);
			return null;
		}
		BasicFileAttributes a = Files.readAttributes(key, BasicFileAttributes.class);
		if (e != null && e.same(a)) {
			e.checked = now;
			return e.text;
		}
		String text = Files.readString(key);
		CACHE.put(key, new Entry(text, a, now));
		return text;
	}
}
