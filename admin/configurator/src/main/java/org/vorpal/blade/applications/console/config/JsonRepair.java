package org.vorpal.blade.applications.console.config;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Undoes what the browser's JavaScript does to a document it only passed
/// through, by comparing the document saved with the file the page loaded:
///
/// - **Integers beyond 2^53.** JavaScript numbers are doubles, so
///   `9223372036854775807` comes back as `9223372036854776000`, which Jackson
///   refuses for a `long`. A number that equals, as a double, exactly one such
///   integer anywhere in the source is that integer.
/// - **Integer-like keys.** A JavaScript object lists keys such as `"1"` or
///   `"300"` first, in ascending order, whatever order the file had; the page
///   cannot even hold another order, so the form is drawn in it too. In a
///   keyed pipeline, rule set or table, order is run order. Each object
///   holding such a key gets back the key order of its source object: the
///   one at the same path, else an equal one elsewhere (the same object,
///   moved or under a renamed key), never one that merely shares keys. A key the source
///   had and the save does not, whose value the save holds under a new key,
///   was renamed: the new key takes its place. Other new keys (an Add) go
///   last. An order the operator typed in the JSON tab wins (see
///   [#repair(JsonNode, List, JsonNode)]).
///
/// Neither match is by position: a list item is its source item only by a
/// unique `id`, so items added, removed or moved above it change nothing.
final class JsonRepair {

	private static final BigInteger JS_EXACT = BigInteger.valueOf(1L << 53);
	private static final long MAX_INDEX = 4294967294L;   // the largest key JavaScript lists as an index

	private JsonRepair() {
	}

	/// Repairs `saved` in place against `source`; true when anything changed.
	static boolean repair(JsonNode saved, JsonNode source) {
		List<JsonNode> sources = new ArrayList<>();
		if (source != null) sources.add(source);
		return repair(saved, sources);
	}

	/// Repairs `saved` in place against `sources`, most trusted first (the text
	/// the form came from, the form's file, the destination, the JSON tab's
	/// text). The first is walked beside `saved` (same key, same id); every one
	/// supplies long integers and key orders to match by value.
	static boolean repair(JsonNode saved, List<JsonNode> sources) {
		return repair(saved, sources, null);
	}

	/// As [#repair(JsonNode, List)], with `editor` the JSON tab's text: where
	/// an object there is in an order JSON.stringify never writes (an index key
	/// after another key, or index keys not ascending), the operator typed that
	/// order, and it is kept. It also supplies long integers, last.
	static boolean repair(JsonNode saved, List<JsonNode> sources, JsonNode editor) {
		if (saved == null) return false;
		List<JsonNode> all = new ArrayList<>(sources);
		if (editor != null) all.add(editor);
		if (all.isEmpty()) return false;
		Map<Double, Set<BigInteger>> big = new HashMap<>();
		List<ObjectNode> keyed = new ArrayList<>();
		for (JsonNode source : sources) collect(source, big, keyed);
		if (editor != null) {
			// The editor's text is the page's own serialization: a long it holds
			// is usually the rounded copy of a source's. Counted only where no
			// source has a number of that double (one typed there), or it would
			// make the source's exact value ambiguous.
			Map<Double, Set<BigInteger>> typed = new HashMap<>();
			collect(editor, typed, keyed);
			typed.forEach((d, values) -> big.putIfAbsent(d, values));
		}
		return walk(saved, all.get(0), editor, big, keyed);
	}

	private static void collect(JsonNode n, Map<Double, Set<BigInteger>> big, List<ObjectNode> keyed) {
		if (n.isIntegralNumber() && n.bigIntegerValue().abs().compareTo(JS_EXACT) > 0) {
			big.computeIfAbsent(n.doubleValue(), k -> new HashSet<>()).add(n.bigIntegerValue());
		} else if (n.isObject()) {
			if (hasIndexKey(n)) keyed.add((ObjectNode) n);
			n.forEach(c -> collect(c, big, keyed));
		} else if (n.isArray()) {
			n.forEach(c -> collect(c, big, keyed));
		}
	}

	/// `atPath` is the source value at the same path, or null.
	private static boolean walk(JsonNode saved, JsonNode atPath, JsonNode editorAt, Map<Double, Set<BigInteger>> big,
			List<ObjectNode> keyed) {
		boolean changed = false;
		if (saved.isObject()) {
			ObjectNode s = (ObjectNode) saved;
			JsonNode same = atPath != null && atPath.isObject() ? atPath : null;
			JsonNode typed = editorAt != null && editorAt.isObject() ? editorAt : null;
			changed |= typedOrder(s, typed) || reorder(s, same, keyed);
			for (Iterator<Map.Entry<String, JsonNode>> it = s.fields(); it.hasNext();) {
				Map.Entry<String, JsonNode> e = it.next();
				JsonNode exact = exactNumber(e.getValue(), big, same == null ? null : same.get(e.getKey()),
						typed == null ? null : typed.get(e.getKey()));
				if (exact != null) {
					e.setValue(exact);
					changed = true;
				} else {
					changed |= walk(e.getValue(), same == null ? null : same.get(e.getKey()),
							typed == null ? null : typed.get(e.getKey()), big, keyed);
				}
			}
		} else if (saved.isArray()) {
			ArrayNode s = (ArrayNode) saved;
			for (int i = 0; i < s.size(); i++) {
				// A list that kept its length: the source's item at this place
				// tells two longs that round alike apart.
				JsonNode samePlace = atPath != null && atPath.isArray() && atPath.size() == s.size() ? atPath.get(i) : null;
				JsonNode typedPlace = editorAt != null && editorAt.isArray() && editorAt.size() == s.size() ? editorAt.get(i) : null;
				JsonNode exact = exactNumber(s.get(i), big, samePlace, typedPlace);
				if (exact != null) {
					s.set(i, exact);
					changed = true;
				} else {
					changed |= walk(s.get(i), sameItem(s.get(i), atPath), sameItem(s.get(i), editorAt), big, keyed);
				}
			}
		}
		return changed;
	}

	/// The source item `item` is, in `list`: the one with its `id`, when exactly
	/// one has it. Never the one at the same index: Add inserts at the top and
	/// Remove shifts the rest, so an index names another item.
	private static JsonNode sameItem(JsonNode item, JsonNode list) {
		if (list == null || !list.isArray() || !item.isObject() || !item.path("id").isTextual()) return null;
		JsonNode found = null;
		for (JsonNode candidate : list) {
			if (candidate.isObject() && item.get("id").equals(candidate.get("id"))) {
				if (found != null) return null;
				found = candidate;
			}
		}
		return found;
	}

	/// The source's integer, when the saved number is exactly one of them
	/// rounded to a double. Two that round alike (Long.MAX_VALUE and one below
	/// it) are told apart by `atPath`, the source's value at the same key.
	/// `typedAt` is the JSON tab's value at the same path: a long there that is
	/// not the page's own rounding of it (JSON.stringify writes the double's
	/// shortest digits, then zeros) was typed, and wins over the source's.
	private static JsonNode exactNumber(JsonNode saved, Map<Double, Set<BigInteger>> big, JsonNode atPath, JsonNode typedAt) {
		if (!saved.isNumber()) return null;
		if (typedAt != null && typedAt.isIntegralNumber() && typedAt.doubleValue() == saved.doubleValue()
				&& typedAt.bigIntegerValue().abs().compareTo(JS_EXACT) > 0
				&& !typedAt.bigIntegerValue().equals(new java.math.BigDecimal(Double.toString(typedAt.doubleValue())).toBigInteger())) {
			if (saved.isIntegralNumber() && saved.bigIntegerValue().equals(typedAt.bigIntegerValue())) return null;
			return com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(typedAt.bigIntegerValue());
		}
		Set<BigInteger> candidates = big.get(saved.doubleValue());
		if (candidates == null) return null;
		BigInteger exact;
		if (candidates.size() == 1) {
			exact = candidates.iterator().next();
		} else if (atPath != null && atPath.isIntegralNumber() && candidates.contains(atPath.bigIntegerValue())) {
			exact = atPath.bigIntegerValue();
		} else {
			return null;
		}
		if (saved.isIntegralNumber() && saved.bigIntegerValue().equals(exact)) return null;
		return com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(exact);
	}

	private static boolean reorder(ObjectNode saved, JsonNode same, List<ObjectNode> keyed) {
		if (!hasIndexKey(saved)) return false;
		List<String> keys = new ArrayList<>();
		saved.fieldNames().forEachRemaining(keys::add);
		JsonNode source = shared(same, keys) > 0 ? same : null;
		if (source == null) {
			int best = 0;
			boolean tie = false;
			for (ObjectNode o : keyed) {
				// Away from its own path, only the same object (moved, or under
				// a renamed key) lends its order: one that merely shares keys
				// ("1", "2") is another map, and its order is not this one's.
				if (!o.equals(saved)) continue;
				int n = shared(o, keys);
				if (n > best) {
					best = n;
					source = o;
					tie = false;
				} else if (n == best && n > 0) {
					tie = true;
				}
			}
			if (tie) source = null;
		}
		if (source == null) return false;
		List<String> was = new ArrayList<>();
		source.fieldNames().forEachRemaining(was::add);
		List<String> gone = new ArrayList<>();
		for (String k : was) {
			if (!saved.has(k)) gone.add(k);
		}
		List<String> added = new ArrayList<>();
		for (String k : keys) {
			if (!source.has(k)) added.add(k);
		}
		// Renames: a dropped key's value turns up under an added key. Paired by
		// value (the saved order is the browser's, so position says nothing);
		// what is left pairs in order when the counts match; added keys with no
		// partner are new, and go last.
		Map<String, String> renamedTo = new HashMap<>();
		List<String> freeAdded = new ArrayList<>(added);
		for (String g : gone) {
			String match = null;
			for (String a : freeAdded) {
				if (saved.get(a).equals(source.get(g))) {
					if (match != null) {   // two candidates: the value says nothing
						match = null;
						break;
					}
					match = a;
				}
			}
			if (match != null) {
				renamedTo.put(g, match);
				freeAdded.remove(match);
			}
		}
		List<String> order = new ArrayList<>();
		for (String k : was) {
			if (saved.has(k)) order.add(k);
			else if (renamedTo.containsKey(k)) order.add(renamedTo.get(k));
		}
		order.addAll(freeAdded);
		if (order.equals(keys)) return false;
		Map<String, JsonNode> values = new LinkedHashMap<>();
		for (String k : order) values.put(k, saved.get(k));
		saved.removeAll();
		saved.setAll(values);
		return true;
	}

	/// The order the operator typed in the JSON tab, when `typed` (the
	/// editor's object at this path) holds the same keys in an order
	/// JSON.stringify never writes; false (nothing done) otherwise.
	private static boolean typedOrder(ObjectNode saved, JsonNode typed) {
		if (typed == null || !hasIndexKey(saved)) return false;
		List<String> keys = new ArrayList<>();
		saved.fieldNames().forEachRemaining(keys::add);
		List<String> order = new ArrayList<>();
		typed.fieldNames().forEachRemaining(order::add);
		if (order.size() != keys.size() || !new HashSet<>(order).equals(new HashSet<>(keys))) return false;
		if (scriptOrder(order)) return false;   // what the page wrote: says nothing
		if (order.equals(keys)) return false;
		Map<String, JsonNode> values = new LinkedHashMap<>();
		for (String k : order) values.put(k, saved.get(k));
		saved.removeAll();
		saved.setAll(values);
		return true;
	}

	/// The order JSON.stringify writes: index keys first, ascending.
	private static boolean scriptOrder(List<String> keys) {
		boolean pastIndexes = false;
		long last = -1;
		for (String k : keys) {
			if (isIndex(k)) {
				if (pastIndexes || Long.parseLong(k) < last) return false;
				last = Long.parseLong(k);
			} else {
				pastIndexes = true;
			}
		}
		return true;
	}

	private static int shared(JsonNode o, List<String> keys) {
		if (o == null) return 0;
		int n = 0;
		for (String k : keys) {
			if (o.has(k)) n++;
		}
		return n;
	}

	private static boolean hasIndexKey(JsonNode o) {
		for (Iterator<String> it = o.fieldNames(); it.hasNext();) {
			if (isIndex(it.next())) return true;
		}
		return false;
	}

	/// A key JavaScript enumerates as an array index: `0`, or digits without a
	/// leading zero, below 2^32 - 1.
	static boolean isIndex(String k) {
		if (!k.matches("0|[1-9][0-9]{0,9}")) return false;
		return Long.parseLong(k) <= MAX_INDEX;
	}
}
