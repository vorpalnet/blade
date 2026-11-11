package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.ConfigFiles;
import org.vorpal.blade.framework.v3.configuration.DraftCheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// What a Save (or a restore) of one configuration file would break on the
/// servers: the problems of the documents they build with the draft, less
/// those the file on disk already causes.
///
/// The same documents as [DraftCheck] (the file merged with the files below
/// it, every file above merged over it, the rule-set rename check), built
/// from the same [ConfigFiles] calls, with two differences the comparison
/// needs. Every check runs: DraftCheck stops at the draft's own problems,
/// so a file that already had one would skip the files above it, and the
/// comparison would see nothing new there. And a file that does not parse
/// is named, not thrown: the draft may be the fix for it.
///
/// Only what this save adds is refused. A file with an old problem (a
/// property its schema now requires) stays saveable, or it could never be
/// saved at all. Problems are compared with list indices removed and
/// counted, so an item inserted above an old problem does not make it new.
final class SaveCheck {

	private SaveCheck() {
	}

	/// The problems saving `draft` as `id` of `app` adds over `before`, the
	/// file as it is on disk (an empty object when there is none, or when it
	/// does not parse).
	static List<String> added(String app, String id, JsonNode draft, JsonNode before, Map<String, String> membership,
			ObjectMapper mapper, DraftCheck.Check check) throws IOException {
		return added(app, id, draft, before, membership, mapper, check, overlayId -> true);
	}

	/// As above; `read` says which cluster or server files some server reads
	/// (a folder left by a renamed one is not checked: nothing loads it).
	/// `before` null: no file, or one that does not parse, so nothing is
	/// already there and every problem counts.
	static List<String> added(String app, String id, JsonNode draft, JsonNode before, Map<String, String> membership,
			ObjectMapper mapper, DraftCheck.Check check, java.util.function.Predicate<String> read) throws IOException {
		List<String> now = problems(app, id, draft, membership, mapper, check, read);
		if (now.isEmpty() || before == null) return now;
		Map<String, Integer> had = new HashMap<>();
		for (String p : problems(app, id, before, membership, mapper, check, read)) had.merge(shape(p), 1, Integer::sum);
		List<String> out = new ArrayList<>();
		for (String p : now) {
			String k = shape(p);
			// A file below that does not parse is never "already there": this
			// file would go live over it unchecked.
			if (p.startsWith(BELOW_BROKEN)) {
				out.add(p);
				continue;
			}
			int n = had.getOrDefault(k, 0);
			if (n > 0) had.put(k, n - 1);
			else out.add(p);
		}
		return out;
	}

	/// Every problem of the documents built with `doc` as file `id`.
	private static final String BELOW_BROKEN = "a file below this one does not parse";

	static List<String> problems(String app, String id, JsonNode doc, Map<String, String> membership, ObjectMapper mapper,
			DraftCheck.Check check) throws IOException {
		return problems(app, id, doc, membership, mapper, check, overlayId -> true);
	}

	static List<String> problems(String app, String id, JsonNode doc, Map<String, String> membership, ObjectMapper mapper,
			DraftCheck.Check check, java.util.function.Predicate<String> read) throws IOException {
		List<String> out = new ArrayList<>();
		boolean domain = ConfigFiles.isDomain(id);
		try {
			JsonNode runs = domain ? doc : ConfigFiles.applied(app, id, doc, membership, mapper);
			for (String p : check.problems(runs, domain)) out.add((domain ? "" : "merged with the files below it, ") + p);
		} catch (IOException unparseable) {
			out.add(BELOW_BROKEN + ", so this save cannot be checked: fix that file first ("
					+ firstLine(unparseable) + ")");
		} catch (Exception e) {
			out.add((domain ? "" : "merged with the files below it, ") + app + " could not load it: " + firstLine(e));
		}
		List<String> unread = new ArrayList<>();
		for (ConfigFiles.Entry over : ConfigFiles.above(app, id, membership)) {
			if (!read.test(over.id)) {
				unread.add(over.path);
				continue;
			}
			JsonNode merged;
			try {
				merged = ConfigFiles.applied(app, over.id, id, doc, membership, mapper);
			} catch (IOException unparseable) {
				// That file, or a cluster file between it and this draft.
				out.add(over.path + " or a file below it does not parse, so this save cannot be checked against it ("
						+ firstLine(unparseable) + "): fix or save that file first");
				continue;
			}
			try {
				for (String p : check.problems(merged, false)) out.add("with " + over.path + ": " + p);
			} catch (Exception e) {
				out.add("with " + over.path + ": could not be loaded merged over this draft: " + firstLine(e));
			}
		}
		// Rule sets live at the top (iRouter) and in every application's crud section.
		for (String section : new String[] { null, "crud" }) {
			try {
				for (String p : ConfigFiles.orphans(app, id, doc, section, membership, mapper)) {
					if (unread.stream().noneMatch(u -> p.startsWith("with " + u + ":"))) out.add(p);
				}
			} catch (IOException unparseable) {
				// a file below that does not parse: named above
			}
		}
		return out;
	}

	/// A problem without its list indices: `plan[3]` and `plan[4]` are one.
	static String shape(String problem) {
		return problem.replaceAll("\\[\\d+\\]", "[]")
				// "is used by 3 objects (A, B, C)": a reorder or a partial fix
				// of an old duplicate is not a new problem.
				.replaceAll("is used by \\d+ objects \\([^)]*\\)", "is used by objects");
	}

	private static String firstLine(Exception e) {
		return String.valueOf(e.getMessage()).split("\n")[0];
	}
}
