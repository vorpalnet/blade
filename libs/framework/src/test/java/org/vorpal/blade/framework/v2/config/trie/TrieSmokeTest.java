package org.vorpal.blade.framework.v2.config.trie;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/// Lightweight smoke driver for [Trie]. Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes \
///          org.vorpal.blade.framework.v2.config.trie.TrieSmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or throws and
/// exits non-zero so the wrapper script can detect failure.
public final class TrieSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testEmpty();
		testPutGet();
		testNullValue();
		testReplaceValue();
		testRemove();
		testRemovePrunes();
		testLongestPrefixOf();
		testLongestPrefixKeyOf();
		testLongestPrefixDialPlan();
		testEmptyKeyAsCatchAll();
		testPrefixesOf();
		testKeySetValues();
		testClear();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
	}

	// ----- individual checks -----

	private static void testEmpty() {
		Trie<String> t = new Trie<>();
		check("empty.size", t.size() == 0);
		check("empty.isEmpty", t.isEmpty());
		check("empty.get", t.get("anything") == null);
		check("empty.containsKey", !t.containsKey("anything"));
		check("empty.longestPrefixOf", t.longestPrefixOf("anything") == null);
		check("empty.longestPrefixKeyOf", t.longestPrefixKeyOf("anything") == null);
		check("empty.prefixesOf", t.prefixesOf("anything").isEmpty());
	}

	private static void testPutGet() {
		Trie<String> t = new Trie<>();
		check("put.first.returnsNull", t.put("hello", "H") == null);
		check("put.second.returnsNull", t.put("help", "P") == null);
		check("put.size", t.size() == 2);
		check("get.hello", "H".equals(t.get("hello")));
		check("get.help", "P".equals(t.get("help")));
		check("get.absent", t.get("hel") == null);
		check("containsKey.hello", t.containsKey("hello"));
		check("containsKey.absent.hel", !t.containsKey("hel"));
	}

	private static void testNullValue() {
		Trie<String> t = new Trie<>();
		t.put("nullkey", null);
		check("nullValue.get", t.get("nullkey") == null);
		check("nullValue.containsKey", t.containsKey("nullkey"));
		check("nullValue.size", t.size() == 1);
	}

	private static void testReplaceValue() {
		Trie<String> t = new Trie<>();
		t.put("k", "v1");
		check("replace.returnsPrevious", "v1".equals(t.put("k", "v2")));
		check("replace.size.unchanged", t.size() == 1);
		check("replace.get.new", "v2".equals(t.get("k")));
	}

	private static void testRemove() {
		Trie<String> t = new Trie<>();
		t.put("a", "A");
		t.put("ab", "AB");
		t.put("abc", "ABC");
		check("remove.returnsValue", "AB".equals(t.remove("ab")));
		check("remove.size", t.size() == 2);
		check("remove.absent.returnsNull", t.remove("nope") == null);
		check("remove.containsKey.gone", !t.containsKey("ab"));
		check("remove.containsKey.sibling", t.containsKey("a"));
		check("remove.containsKey.descendant", t.containsKey("abc"));
	}

	private static void testRemovePrunes() {
		Trie<String> t = new Trie<>();
		t.put("hello", "H");
		t.put("helmet", "M");
		t.remove("helmet");
		// "hello" must still be reachable; pruning of the "helmet" branch
		// must not have damaged the shared "hel" prefix path.
		check("removePrunes.hello.intact", "H".equals(t.get("hello")));
		check("removePrunes.helmet.gone", !t.containsKey("helmet"));
		// Now also remove "hello" — trie should become empty.
		t.remove("hello");
		check("removePrunes.empty", t.isEmpty());
	}

	private static void testLongestPrefixOf() {
		Trie<String> t = new Trie<>();
		t.put("1", "NANP");
		t.put("1816", "Kansas City");
		t.put("18165551234", "Specific number");
		check("lpo.exact", "Specific number".equals(t.longestPrefixOf("18165551234")));
		check("lpo.kcArea", "Kansas City".equals(t.longestPrefixOf("18165559876")));
		check("lpo.nanp", "NANP".equals(t.longestPrefixOf("12125550000")));
		check("lpo.noMatch", t.longestPrefixOf("442075550000") == null);
	}

	private static void testLongestPrefixKeyOf() {
		Trie<String> t = new Trie<>();
		t.put("1", "NANP");
		t.put("1816", "Kansas City");
		check("lpko.area", "1816".equals(t.longestPrefixKeyOf("18165559876")));
		check("lpko.country", "1".equals(t.longestPrefixKeyOf("12125550000")));
		check("lpko.noMatch", t.longestPrefixKeyOf("442075550000") == null);
	}

	private static void testLongestPrefixDialPlan() {
		// More realistic dial plan, mixed-length prefixes, with values
		// the BLADE prefix-block app would care about.
		Trie<String> t = new Trie<>();
		t.put("1", "domestic");
		t.put("1900", "premium-block");
		t.put("1976", "premium-block");
		t.put("011", "international");
		t.put("01144", "uk");
		t.put("01133", "fr");
		check("dial.intl.uk", "uk".equals(t.longestPrefixOf("0114420755512345")));
		check("dial.intl.fr", "fr".equals(t.longestPrefixOf("0113312345678")));
		check("dial.intl.fallback", "international".equals(t.longestPrefixOf("011491234567890")));
		check("dial.premium", "premium-block".equals(t.longestPrefixOf("19005551234")));
		check("dial.normal", "domestic".equals(t.longestPrefixOf("18165551234")));
	}

	private static void testEmptyKeyAsCatchAll() {
		Trie<String> t = new Trie<>();
		t.put("", "DEFAULT");
		t.put("1", "domestic");
		check("emptyKey.exact", "DEFAULT".equals(t.get("")));
		check("emptyKey.size", t.size() == 2);
		// "" is a prefix of everything, so longestPrefixOf falls back to it
		// when no longer match exists.
		check("emptyKey.fallback", "DEFAULT".equals(t.longestPrefixOf("442075551234")));
		check("emptyKey.notFallbackWhenLongerMatches",
				"domestic".equals(t.longestPrefixOf("18165551234")));
	}

	private static void testPrefixesOf() {
		Trie<String> t = new Trie<>();
		t.put("1", "A");
		t.put("18", "B");
		t.put("1816", "C");
		t.put("99", "Z");
		List<Map.Entry<String, String>> matches = t.prefixesOf("18165551234");
		check("prefixesOf.count", matches.size() == 3);
		check("prefixesOf.0.key", "1".equals(matches.get(0).getKey()));
		check("prefixesOf.1.key", "18".equals(matches.get(1).getKey()));
		check("prefixesOf.2.key", "1816".equals(matches.get(2).getKey()));
		check("prefixesOf.0.val", "A".equals(matches.get(0).getValue()));
		check("prefixesOf.2.val", "C".equals(matches.get(2).getValue()));
		check("prefixesOf.noMatch", t.prefixesOf("442075551234").isEmpty());
	}

	private static void testKeySetValues() {
		Trie<String> t = new Trie<>();
		t.put("a", "A");
		t.put("ab", "AB");
		t.put("xyz", "X");
		HashSet<String> expectedKeys = new HashSet<>(Arrays.asList("a", "ab", "xyz"));
		HashSet<String> expectedVals = new HashSet<>(Arrays.asList("A", "AB", "X"));
		check("keySet.size", t.keySet().size() == 3);
		check("keySet.contents", t.keySet().equals(expectedKeys));
		check("values.size", t.values().size() == 3);
		check("values.contents", new HashSet<>(t.values()).equals(expectedVals));
	}

	private static void testClear() {
		Trie<String> t = new Trie<>();
		t.put("k1", "v1");
		t.put("k2", "v2");
		t.clear();
		check("clear.size", t.size() == 0);
		check("clear.isEmpty", t.isEmpty());
		check("clear.get", t.get("k1") == null);
		check("clear.longestPrefixOf", t.longestPrefixOf("k1") == null);
	}

	// ----- harness -----

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}
}
