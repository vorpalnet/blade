package org.vorpal.blade.framework.v3.configuration.trie;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// A generic prefix-search trie keyed by [String], holding values of type `V`.
///
/// The primary feature is [#longestPrefixOf(String)] — given an input string,
/// return the value of the longest stored key that is a prefix of the input.
/// This is the standard "dial plan lookup" operation in telco routing
/// (e.g. find the most specific rule for an incoming phone number).
///
/// ## Why this exists
///
/// BLADE's earlier prefix-map implementations were either:
///   - **HashMap with substring iteration** — `O(L²)` per lookup (where `L` is
///     input length); fine for tiny tables but wasteful as the table grows
///     because every lookup re-hashes every prefix length;
///   - **`PatriciaTrie` from `commons-collections4`** — sometimes failed to
///     return the correct longest-prefix match; debugging the radix-tree
///     internals was not worth the hassle.
///
/// This class delivers `O(L)` longest-prefix matching with no external
/// dependencies. The implementation is a straightforward per-character trie
/// using a [HashMap] of children at each node — easy to read, easy to verify,
/// fast enough for telco workloads (digit-only or short-alphabet keys with
/// table sizes from tens to tens of thousands).
///
/// ## Null values
///
/// Null values are permitted. The trie distinguishes "key absent" from "key
/// present with `null` value" via a per-node `terminal` flag, so calling
/// [#put(String, Object)] with a `null` value still increments [#size()] and
/// causes [#containsKey(String)] to return `true`.
///
/// ## Thread safety
///
/// Not thread-safe. External synchronisation is required if a single trie is
/// accessed from multiple threads. The intended BLADE usage is "load once at
/// service start, read many times concurrently afterwards" — for that pattern
/// the trie is safe to share across threads as long as no further writes
/// occur after publication.
///
/// @param <V> the value type associated with each key
public class Trie<V> implements Serializable {
	private static final long serialVersionUID = 1L;

	private final Node<V> root = new Node<>();
	private int size;

	public Trie() {
	}

	/// Constructs a trie pre-populated with the contents of the given map.
	public Trie(Map<String, ? extends V> initial) {
		Objects.requireNonNull(initial, "initial");
		for (Map.Entry<String, ? extends V> e : initial.entrySet()) {
			put(e.getKey(), e.getValue());
		}
	}

	// ------------------------------------------------------------------
	//  Core map operations
	// ------------------------------------------------------------------

	/// Inserts (or replaces) the value associated with `key`.
	///
	/// @param key   the key; must not be null
	/// @param value the value to associate (may be null)
	/// @return the previous value, or null if the key was absent
	public V put(String key, V value) {
		Objects.requireNonNull(key, "key");
		Node<V> node = root;
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (node.children == null) {
				node.children = new HashMap<>(4);
			}
			Node<V> next = node.children.get(c);
			if (next == null) {
				next = new Node<>();
				node.children.put(c, next);
			}
			node = next;
		}
		V previous = node.terminal ? node.value : null;
		if (!node.terminal) {
			size++;
		}
		node.terminal = true;
		node.value = value;
		return previous;
	}

	/// Returns the value associated with the exact `key`, or `null` if absent.
	///
	/// Note that a return value of `null` may also mean "the key is present
	/// with a null value". Use [#containsKey(String)] to disambiguate.
	public V get(String key) {
		Objects.requireNonNull(key, "key");
		Node<V> node = findNode(key);
		return (node != null && node.terminal) ? node.value : null;
	}

	/// Returns true if the trie contains the exact `key`.
	public boolean containsKey(String key) {
		Objects.requireNonNull(key, "key");
		Node<V> node = findNode(key);
		return node != null && node.terminal;
	}

	/// Removes the entry for `key`. Returns the previous value, or null if
	/// the key was absent.
	public V remove(String key) {
		Objects.requireNonNull(key, "key");
		// Walk down recording the path so we can prune empty branches.
		List<Node<V>> path = new ArrayList<>(key.length() + 1);
		List<Character> edges = new ArrayList<>(key.length());
		Node<V> node = root;
		path.add(node);
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (node.children == null) {
				return null;
			}
			Node<V> next = node.children.get(c);
			if (next == null) {
				return null;
			}
			node = next;
			path.add(node);
			edges.add(c);
		}
		if (!node.terminal) {
			return null;
		}
		V previous = node.value;
		node.terminal = false;
		node.value = null;
		size--;

		// Prune empty leaf nodes upward.
		for (int i = path.size() - 1; i > 0; i--) {
			Node<V> n = path.get(i);
			boolean hasChildren = n.children != null && !n.children.isEmpty();
			if (n.terminal || hasChildren) {
				break;
			}
			Node<V> parent = path.get(i - 1);
			parent.children.remove(edges.get(i - 1));
			if (parent.children.isEmpty()) {
				parent.children = null;
			}
		}
		return previous;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public void clear() {
		root.children = null;
		root.terminal = false;
		root.value = null;
		size = 0;
	}

	// ------------------------------------------------------------------
	//  Prefix operations
	// ------------------------------------------------------------------

	/// Returns the value of the **longest** stored key that is a prefix of
	/// `input`, or `null` if no stored key is a prefix of it.
	///
	/// Example: with keys `"1"`, `"1816"`, `"18165551234"`, calling
	/// `longestPrefixOf("18165559876")` returns the value associated with
	/// `"1816"` (the longest matching prefix).
	///
	/// As with [#get(String)], a `null` return may mean either "no match"
	/// or "longest matching key has a `null` value". Use
	/// [#longestPrefixKeyOf(String)] to disambiguate.
	public V longestPrefixOf(String input) {
		Objects.requireNonNull(input, "input");
		Node<V> node = root;
		V best = null;
		boolean haveBest = false;
		if (node.terminal) { // empty key "" is allowed and acts as catch-all
			best = node.value;
			haveBest = true;
		}
		for (int i = 0; i < input.length(); i++) {
			if (node.children == null) {
				break;
			}
			Node<V> next = node.children.get(input.charAt(i));
			if (next == null) {
				break;
			}
			node = next;
			if (node.terminal) {
				best = node.value;
				haveBest = true;
			}
		}
		return haveBest ? best : null;
	}

	/// Returns the longest stored key that is a prefix of `input`, or `null`
	/// if no stored key is a prefix of it. Useful when the caller needs to
	/// know **which** key matched (not just its value).
	public String longestPrefixKeyOf(String input) {
		Objects.requireNonNull(input, "input");
		Node<V> node = root;
		int bestLen = -1;
		if (node.terminal) {
			bestLen = 0;
		}
		for (int i = 0; i < input.length(); i++) {
			if (node.children == null) {
				break;
			}
			Node<V> next = node.children.get(input.charAt(i));
			if (next == null) {
				break;
			}
			node = next;
			if (node.terminal) {
				bestLen = i + 1;
			}
		}
		return (bestLen >= 0) ? input.substring(0, bestLen) : null;
	}

	/// Returns all `(key, value)` entries whose key is a prefix of `input`,
	/// **shortest first**. The returned list is independent of the trie and
	/// may be modified by the caller.
	public List<Map.Entry<String, V>> prefixesOf(String input) {
		Objects.requireNonNull(input, "input");
		List<Map.Entry<String, V>> matches = new ArrayList<>();
		Node<V> node = root;
		if (node.terminal) {
			matches.add(new AbstractMap.SimpleImmutableEntry<>("", node.value));
		}
		for (int i = 0; i < input.length(); i++) {
			if (node.children == null) {
				break;
			}
			Node<V> next = node.children.get(input.charAt(i));
			if (next == null) {
				break;
			}
			node = next;
			if (node.terminal) {
				matches.add(new AbstractMap.SimpleImmutableEntry<>(input.substring(0, i + 1), node.value));
			}
		}
		return matches;
	}

	// ------------------------------------------------------------------
	//  Bulk views
	// ------------------------------------------------------------------

	/// Returns a snapshot of every key in the trie. The returned set is
	/// independent of the trie. Iteration order is depth-first, lexicographic
	/// only within a single node's children (which are unordered HashMap
	/// children, so absolute lexicographic ordering is **not** guaranteed).
	public Set<String> keySet() {
		Set<String> keys = new LinkedHashSet<>(size);
		collect(root, new StringBuilder(), keys, null);
		return keys;
	}

	/// Returns a snapshot of every value in the trie, in the same iteration
	/// order as [#keySet()]. May contain duplicate values and `null`s.
	public Collection<V> values() {
		List<V> values = new ArrayList<>(size);
		collect(root, new StringBuilder(), null, values);
		return values;
	}

	private void collect(Node<V> node, StringBuilder path, Set<String> keys, Collection<V> values) {
		if (node.terminal) {
			if (keys != null) keys.add(path.toString());
			if (values != null) values.add(node.value);
		}
		if (node.children == null) {
			return;
		}
		for (Map.Entry<Character, Node<V>> e : node.children.entrySet()) {
			path.append(e.getKey());
			collect(e.getValue(), path, keys, values);
			path.setLength(path.length() - 1);
		}
	}

	// ------------------------------------------------------------------
	//  Internals
	// ------------------------------------------------------------------

	private Node<V> findNode(String key) {
		Node<V> node = root;
		for (int i = 0; i < key.length(); i++) {
			if (node.children == null) {
				return null;
			}
			node = node.children.get(key.charAt(i));
			if (node == null) {
				return null;
			}
		}
		return node;
	}

	@Override
	public String toString() {
		return "Trie{size=" + size + "}";
	}

	private static final class Node<V> implements Serializable {
		private static final long serialVersionUID = 1L;

		boolean terminal;
		V value;
		HashMap<Character, Node<V>> children;
	}
}
