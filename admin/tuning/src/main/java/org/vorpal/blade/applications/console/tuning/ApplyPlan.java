package org.vorpal.blade.applications.console.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What an apply would do to each target, computed before any edit session opens.
///
/// A [Change] is the before/after of one target's `ServerStart.Arguments` broken into the knobs
/// that were added, removed and changed (keyed by [JvmSettings#argumentKey], so `-Xmx512m` to
/// `-Xmx8g` is one change, not a removal plus an addition), the tokens the profile left alone,
/// and the warnings the combination earns. The same object is returned by a dry-run preview
/// and by the real apply, so the operator sees the diff before committing and gets the same
/// diff back as the receipt.
///
/// Everything here is a pure function of strings and numbers so it can be unit-tested without a
/// domain. The MBean walking that feeds it lives in `JvmSettings` and `ServerStartTargets`.
final class ApplyPlan {

	/// One knob whose value changes.
	static final class Delta {
		final String key;
		final String from;
		final String to;

		Delta(String key, String from, String to) {
			this.key = key;
			this.from = from;
			this.to = to;
		}
	}

	/// The planned change for one target.
	static final class Change {
		final String target;
		final String kind;
		final String profile;
		final String before;
		final String after;
		final List<String> added = new ArrayList<>();
		final List<String> removed = new ArrayList<>();
		final List<Delta> changed = new ArrayList<>();
		final List<String> preserved = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		boolean ok = true;
		String error;

		Change(String target, String kind, String profile, String before, String after) {
			this.target = target;
			this.kind = kind;
			this.profile = profile;
			this.before = before == null ? "" : before;
			this.after = after == null ? "" : after;
		}

		/// A change that could not be planned (no ServerStart MBean, an exception).
		static Change failed(String target, String kind, String error) {
			Change c = new Change(target, kind, "", "", "");
			c.ok = false;
			c.error = error;
			return c;
		}

		/// True when no knob is added, removed or changed. The merged text may still differ from
		/// the live line (the overlay moves profile tokens to the end), so this, not string
		/// equality, is what decides whether an apply writes anything.
		boolean isUnchanged() {
			return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
		}

		ObjectNode toJson(ObjectMapper mapper) {
			ObjectNode n = mapper.createObjectNode();
			n.put("target", target);
			n.put("kind", kind);
			n.put("ok", ok);
			if (error != null) n.put("error", error);
			n.put("profile", profile);
			n.put("before", before);
			n.put("after", after);
			n.put("unchanged", isUnchanged());
			ArrayNode a = n.putArray("added");
			for (String s : added) a.add(s);
			ArrayNode r = n.putArray("removed");
			for (String s : removed) r.add(s);
			ArrayNode c = n.putArray("changed");
			for (Delta d : changed) {
				ObjectNode dn = c.addObject();
				dn.put("key", d.key);
				dn.put("from", d.from);
				dn.put("to", d.to);
			}
			ArrayNode p = n.putArray("preserved");
			for (String s : preserved) p.add(s);
			ArrayNode w = n.putArray("warnings");
			for (String s : warnings) w.add(s);
			return n;
		}
	}

	private ApplyPlan() {
	}

	/// Diff two argument lines by knob. Tokens sharing a key are compared as a group so a
	/// repeated key (two `-Xlog` tokens) is one entry.
	static Change diff(String target, String kind, String profile, String before, String after,
			List<String> preserved) {
		Change c = new Change(target, kind, profile, before, after);
		if (preserved != null) c.preserved.addAll(preserved);

		Map<String, List<String>> was = byKey(JvmSettings.tokenize(before));
		Map<String, List<String>> now = byKey(JvmSettings.tokenize(after));

		for (Map.Entry<String, List<String>> e : was.entrySet()) {
			if (!now.containsKey(e.getKey())) c.removed.add(String.join(" ", e.getValue()));
		}
		for (Map.Entry<String, List<String>> e : now.entrySet()) {
			List<String> old = was.get(e.getKey());
			String to = String.join(" ", e.getValue());
			if (old == null) {
				c.added.add(to);
			} else {
				String from = String.join(" ", old);
				if (!from.equals(to)) c.changed.add(new Delta(e.getKey(), from, to));
			}
		}
		return c;
	}

	/// True when two argument lines are the same knobs with the same values, whitespace aside.
	static boolean sameArguments(String a, String b) {
		return String.join(" ", JvmSettings.tokenize(a)).equals(String.join(" ", JvmSettings.tokenize(b)));
	}

	/// True when two classpaths are the same entries in the same order, whitespace aside.
	static boolean sameClassPath(String a, String b) {
		return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
	}

	// ---- warnings ---------------------------------------------------------------------------

	/// install.sh leaves Metaspace unbounded on purpose: a cap has OOMed a full BLADE deploy
	/// partway through. Introducing one where the live line has none deserves a flag.
	static void warnMetaspaceCap(Change c) {
		boolean had = byKey(JvmSettings.tokenize(c.before)).containsKey("-XX:MaxMetaspaceSize");
		boolean has = byKey(JvmSettings.tokenize(c.after)).containsKey("-XX:MaxMetaspaceSize");
		if (!had && has) {
			c.warnings.add("Adds -XX:MaxMetaspaceSize where the server had none. install.sh leaves Metaspace"
					+ " unbounded because a cap has OOMed a full BLADE deploy partway through; a cap that is too"
					+ " low stops the server coming up after the admin EAR deploys.");
		}
	}

	/// One template line serves every dynamic engine, so nothing on the AdminServer can give
	/// `${server}` a per-engine value.
	static void warnTemplateServerVar(Change c) {
		if ("template".equals(c.kind) && c.after.contains("${server}")) {
			c.warnings.add("${server} is left as written: a template's one argument line serves every dynamic"
					+ " engine, so it cannot vary per node. Use a shared path, or a per-server target.");
		}
	}

	/// Why this ClassPath will not boot an OCCAS engine, or null if it looks complete.
	static String classPathWarning(String classPath) {
		String cp = classPath == null ? "" : classPath.trim();
		if (cp.isEmpty()) {
			return "ClassPath is empty. In MBean-mode start Node Manager builds the java line from ServerStart"
					+ " alone, so the server would launch with no SIP jars and no SIP container.";
		}
		if (!cp.contains("weblogic_sip.jar")) {
			return "ClassPath has no weblogic_sip.jar. The server would boot with no SIP container"
					+ " (SipServerBean ClassNotFound).";
		}
		if (!cp.contains("weblogic.jar")) {
			return "ClassPath has no weblogic.jar. Node Manager cannot launch weblogic.Server without it.";
		}
		return null;
	}

	/// The pinned heap the admin box would have to commit, against what it has. `argsByTarget` is
	/// every target that runs on the admin machine, with the argument line it would have after the
	/// apply. Returns a warning when the -Xmx sum passes 85% of physical RAM, or null.
	static String adminBoxHeapWarning(Map<String, String> argsByTarget, long ramTotalMB) {
		if (ramTotalMB <= 0 || argsByTarget.size() < 1) return null;
		long sumMB = 0;
		boolean pretouch = false;
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : argsByTarget.entrySet()) {
			String xmx = valueOf(e.getValue(), "-Xmx");
			long bytes = parseSize(xmx);
			if (bytes <= 0) continue;
			sumMB += bytes / (1024L * 1024L);
			parts.add(e.getKey() + " " + xmx);
			if (e.getValue().contains("-XX:+AlwaysPreTouch")) pretouch = true;
		}
		if (sumMB * 100 < ramTotalMB * 85) return null;
		return "Heap on the admin box would be " + String.join(" + ", parts) + " = " + fmtMB(sumMB) + " on a "
				+ fmtMB(ramTotalMB) + " host" + (pretouch ? ", pre-touched at startup" : "")
				+ ". These JVMs share one machine with the OS and Coherence off-heap; the kernel will kill one"
				+ " rather than let them all commit.";
	}

	// ---- helpers ----------------------------------------------------------------------------

	private static Map<String, List<String>> byKey(List<String> tokens) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (String t : tokens) {
			out.computeIfAbsent(JvmSettings.argumentKey(t), k -> new ArrayList<>()).add(t);
		}
		return out;
	}

	/// The value after `prefix` for the first token that starts with it, or "".
	static String valueOf(String args, String prefix) {
		for (String t : JvmSettings.tokenize(args)) {
			if (t.startsWith(prefix)) return t.substring(prefix.length());
		}
		return "";
	}

	private static final Pattern SIZE = Pattern.compile("^(\\d+)([kKmMgGtT]?)$");

	/// A JVM size token (`8g`, `512m`, `1024k`, bare bytes) in bytes; 0 if unparseable.
	static long parseSize(String v) {
		if (v == null) return 0;
		Matcher m = SIZE.matcher(v.trim());
		if (!m.matches()) return 0;
		long n = Long.parseLong(m.group(1));
		switch (m.group(2).toLowerCase()) {
		case "k":
			return n * 1024L;
		case "m":
			return n * 1024L * 1024L;
		case "g":
			return n * 1024L * 1024L * 1024L;
		case "t":
			return n * 1024L * 1024L * 1024L * 1024L;
		default:
			return n;
		}
	}

	private static String fmtMB(long mb) {
		return mb >= 1024 ? String.format("%.1f GB", mb / 1024.0) : mb + " MB";
	}
}
