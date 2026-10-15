package org.vorpal.blade.applications.dashboard;

import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/// Whether an application runs the configuration file the AdminServer holds
/// for it: every value the file sets must be the value the application runs
/// (its Configuration MBean's `currentJson`). A value only the running copy
/// has is a default the file leaves out, and does not count.
///
/// A difference is a push that failed on that server, a file edited by hand,
/// or an application that never reloaded.
final class ConfigDrift {

	private ConfigDrift() {
	}

	/// The first place the running configuration does not carry the file's
	/// value: `differs at <path>` when it runs another value, `does not know
	/// <path>` when the file sets something the application has no setting
	/// for (a setting renamed or removed, or an application built before it
	/// existed). Null when it carries them all.
	///
	/// Lists compare item by item under the same rule, matched by each item's
	/// `id` or `name` when every item has a different one (their order is not
	/// meaning), else by position. Numbers compare by value, so 5 and 5.0 agree. A
	/// running value of `********` is a secret the application will not
	/// show, and is taken to agree.
	static String firstDifference(JsonNode file, JsonNode running) {
		return difference("", file, running);
	}

	static final String MASKED = "********";

	private static String difference(String path, JsonNode file, JsonNode running) {
		if (file == null || file.isNull()) {
			return null;
		}
		if (running == null || running.isMissingNode()) {
			return "does not know " + (path.isEmpty() ? "(everything)" : path);
		}
		if (running.isTextual() && MASKED.equals(running.asText())) {
			return null;
		}
		if (file.isObject()) {
			if (!running.isObject()) {
				return differs(path);
			}
			for (Iterator<Map.Entry<String, JsonNode>> it = file.fields(); it.hasNext();) {
				Map.Entry<String, JsonNode> e = it.next();
				String found = difference(path.isEmpty() ? e.getKey() : path + '.' + e.getKey(), e.getValue(),
						running.get(e.getKey()));
				if (found != null) {
					return found;
				}
			}
			return null;
		}
		if (file.isArray()) {
			if (!running.isArray() || running.size() != file.size()) {
				return differs(path);
			}
			String key = keyOf(file) != null && keyOf(file).equals(keyOf(running)) ? keyOf(file) : null;
			for (int i = 0; i < file.size(); i++) {
				JsonNode item = file.get(i), match = running.get(i);
				if (key != null) {
					match = null;
					for (JsonNode r : running) {
						if (r.get(key).equals(item.get(key))) {
							match = r;
							break;
						}
					}
					if (match == null) {
						return differs(path);
					}
				}
				String found = difference(path + '[' + (key != null ? item.get(key).asText() : String.valueOf(i)) + ']',
						item, match);
				if (found != null) {
					return found.startsWith("does not know") ? found : differs(path);
				}
			}
			return null;
		}
		if (file.isNumber() && running.isNumber()) {
			return file.decimalValue().compareTo(running.decimalValue()) == 0 ? null : differs(path);
		}
		return file.equals(running) ? null : differs(path);
	}

	private static String differs(String path) {
		return "differs at " + (path.isEmpty() ? "(everything)" : path);
	}

	/// `id` or `name` when every item of the list is an object that has one,
	/// and no two share it. Selectors may repeat an `id` on purpose (the same
	/// value tried from two headers, in order), and then position is meaning.
	private static String keyOf(JsonNode list) {
		for (String key : new String[] { "id", "name" }) {
			java.util.Set<JsonNode> seen = new java.util.HashSet<>();
			boolean unique = list.size() > 0;
			for (JsonNode item : list) {
				unique &= item.isObject() && item.hasNonNull(key) && seen.add(item.get(key));
			}
			if (unique) {
				return key;
			}
		}
		return null;
	}
}
