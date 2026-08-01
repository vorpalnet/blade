package org.vorpal.blade.applications.console.mxgraph;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/// Structural difference between the configuration on disk and the one about to
/// replace it.
///
/// Publishing overwrites a live routing config, and the editor only models part
/// of it — root blocks like `logging`, `analytics` and `events` ride through
/// untouched *if* the operator loaded the live config first. Load a sample
/// instead, edit, publish, and those blocks are gone. `.versions/` makes that
/// recoverable; showing the difference beforehand makes it avoidable.
///
/// Entries are ordered shallowest-first so a whole root block disappearing
/// leads, rather than being buried under transition-level noise.
public final class FsmarDiff {

	/// Beyond this the list stops being readable. Truncation is reported, never
	/// silent — see [Result#isTruncated].
	static final int MAX_ENTRIES = 200;

	/// Values longer than this are elided in the report; the point is *what*
	/// changed, and a full route template drowns that.
	private static final int MAX_VALUE = 80;

	private FsmarDiff() {
	}

	/// What happened at one location.
	public enum Op {
		/// Present in the new config, absent from the live one.
		ADDED,
		/// Present in the live config, absent from the new one — the dangerous
		/// direction, and why this exists.
		REMOVED,
		/// Present in both, different value.
		CHANGED
	}

	public static final class Entry {
		private final Op op;
		private final String path;
		private final String from;
		private final String to;

		Entry(Op op, String path, String from, String to) {
			this.op = op;
			this.path = path;
			this.from = from;
			this.to = to;
		}

		public Op getOp() {
			return op;
		}

		public String getPath() {
			return path;
		}

		/// The live value, null for an addition.
		public String getFrom() {
			return from;
		}

		/// The value about to be written, null for a removal.
		public String getTo() {
			return to;
		}

		/// Depth, used to sort shallow (structural) changes first.
		int depth() {
			int n = 0;
			for (int i = 0; i < path.length(); i++) {
				if (path.charAt(i) == '/') {
					n++;
				}
			}
			return n;
		}
	}

	public static final class Result {
		private final List<Entry> entries;
		private final boolean truncated;
		private final boolean targetExists;

		Result(List<Entry> entries, boolean truncated, boolean targetExists) {
			this.entries = entries;
			this.truncated = truncated;
			this.targetExists = targetExists;
		}

		public List<Entry> getEntries() {
			return entries;
		}

		/// True when the entry list was capped at [#MAX_ENTRIES].
		public boolean isTruncated() {
			return truncated;
		}

		/// False when nothing has been published to this target yet — then
		/// there is nothing to lose and the whole config counts as added.
		public boolean isTargetExists() {
			return targetExists;
		}

		public boolean isIdentical() {
			return entries.isEmpty();
		}

		public long count(Op op) {
			return entries.stream().filter(e -> e.getOp() == op).count();
		}

		/// Root-level keys the new config drops — `logging`, `analytics`,
		/// `events` and friends. The headline risk, called out separately so
		/// the UI can lead with it.
		public List<String> removedRootKeys() {
			List<String> out = new ArrayList<>();
			for (Entry e : entries) {
				if (e.getOp() == Op.REMOVED && e.depth() == 1) {
					out.add(e.getPath().substring(1));
				}
			}
			return out;
		}
	}

	/// Compares `live` (may be null — nothing published yet) against `proposed`.
	public static Result compare(JsonNode live, JsonNode proposed) {
		List<Entry> entries = new ArrayList<>();
		boolean targetExists = live != null && !live.isMissingNode() && !live.isNull();
		if (targetExists) {
			walk("", live, proposed, entries);
		}
		boolean truncated = entries.size() > MAX_ENTRIES;
		// Shallowest first, then by path, so structural changes lead. Stable
		// sort keeps discovery order within a depth.
		entries.sort((a, b) -> {
			int byDepth = Integer.compare(a.depth(), b.depth());
			return (byDepth != 0) ? byDepth : a.getPath().compareTo(b.getPath());
		});
		if (truncated) {
			entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
		}
		return new Result(entries, truncated, targetExists);
	}

	private static void walk(String path, JsonNode live, JsonNode proposed, List<Entry> out) {
		if (live.isObject() && proposed.isObject()) {
			Set<String> names = new LinkedHashSet<>();
			live.fieldNames().forEachRemaining(names::add);
			proposed.fieldNames().forEachRemaining(names::add);
			for (String name : names) {
				String child = path + "/" + name;
				boolean inLive = live.has(name);
				boolean inNew = proposed.has(name);
				if (inLive && !inNew) {
					out.add(new Entry(Op.REMOVED, child, describe(live.get(name)), null));
				} else if (!inLive && inNew) {
					out.add(new Entry(Op.ADDED, child, null, describe(proposed.get(name))));
				} else {
					walk(child, live.get(name), proposed.get(name), out);
				}
			}
			return;
		}

		if (live.isArray() && proposed.isArray()) {
			// Order is semantic here (transitions are first-match-wins), so
			// compare by position rather than trying to match elements up.
			int shared = Math.min(live.size(), proposed.size());
			for (int i = 0; i < shared; i++) {
				walk(path + "/" + i, live.get(i), proposed.get(i), out);
			}
			for (int i = shared; i < live.size(); i++) {
				out.add(new Entry(Op.REMOVED, path + "/" + i, describe(live.get(i)), null));
			}
			for (int i = shared; i < proposed.size(); i++) {
				out.add(new Entry(Op.ADDED, path + "/" + i, null, describe(proposed.get(i))));
			}
			return;
		}

		if (!live.equals(proposed)) {
			out.add(new Entry(Op.CHANGED, path, describe(live), describe(proposed)));
		}
	}

	/// A short rendering of a value. Containers are summarised by shape rather
	/// than dumped, since the path already says where they are.
	private static String describe(JsonNode node) {
		if (node == null || node.isNull()) {
			return "null";
		}
		if (node.isObject()) {
			StringBuilder sb = new StringBuilder("{");
			Iterator<String> it = node.fieldNames();
			int shown = 0;
			while (it.hasNext() && shown < 3) {
				if (shown > 0) {
					sb.append(", ");
				}
				sb.append(it.next());
				shown++;
			}
			if (it.hasNext()) {
				sb.append(", …");
			}
			return sb.append("}").toString();
		}
		if (node.isArray()) {
			return "[" + node.size() + " item" + (node.size() == 1 ? "" : "s") + "]";
		}
		String text = node.asText();
		return (text.length() > MAX_VALUE) ? text.substring(0, MAX_VALUE) + "…" : text;
	}
}
