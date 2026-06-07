package org.vorpal.blade.framework.v3.configuration.selectors;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke test for [TableSelector] — classification/tiering as data.
/// Covers hash and prefix lookups, namespaced + bare storage, the no-match
/// fallthrough, chaining off a prior selector's extraction, and the JSON
/// round-trip of the `table` subtype.
public final class TableSelectorSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		testHashTier();
		testPrefixDialPlan();
		testNoMatch();
		testChainedFromRegex();
		testJsonRoundTrip();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	private static TableSelector tierSelector() {
		TranslationTable table = new TranslationTable();
		table.setKeyExpression("${user}");
		table.createTranslation("alice").put("tier", "gold");
		table.createTranslation("bob").put("tier", "silver");
		return new TableSelector("customerTier", table);
	}

	private static void testHashTier() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "alice");
		tierSelector().extract(ctx, null);
		check("hash.bare", "gold".equals(ctx.get("tier")));
		check("hash.namespaced", "gold".equals(ctx.get("customerTier.tier")));

		MemoryContext ctx2 = new MemoryContext();
		ctx2.put("user", "bob");
		tierSelector().extract(ctx2, null);
		check("hash.second.row", "silver".equals(ctx2.get("tier")));
	}

	private static void testPrefixDialPlan() {
		TranslationTable table = new TranslationTable();
		table.setMatch(MatchStrategy.prefix);
		table.setKeyExpression("${dialed}");
		table.createTranslation("1408").put("region", "bayarea");
		table.createTranslation("1").put("region", "us");

		TableSelector sel = new TableSelector("dialPlan", table);

		MemoryContext ctx = new MemoryContext();
		ctx.put("dialed", "14085551212");
		sel.extract(ctx, null);
		check("prefix.longest", "bayarea".equals(ctx.get("region")));

		MemoryContext ctx2 = new MemoryContext();
		ctx2.put("dialed", "12125551212");
		sel.extract(ctx2, null);
		check("prefix.fallback", "us".equals(ctx2.get("region")));
	}

	private static void testNoMatch() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "mallory");
		tierSelector().extract(ctx, null);
		check("nomatch.nothing.stored", ctx.get("tier") == null);

		// unresolvable key (variable never extracted) → no lookup, no store
		MemoryContext ctx2 = new MemoryContext();
		tierSelector().extract(ctx2, null);
		check("nomatch.unresolved.key", ctx2.get("tier") == null);
	}

	private static void testChainedFromRegex() {
		// A RegexSelector extracts ${From.user} from a header payload; the
		// TableSelector then classifies it — the FSMAR per-state pattern.
		Map<String, String> payload = new HashMap<>();
		payload.put("From", "\"Alice\" <sip:alice@example.com>;tag=1928301774");

		RegexSelector from = new RegexSelector("From", "From",
				".*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*", null);

		TranslationTable table = new TranslationTable();
		table.setKeyExpression("${From.user}");
		table.createTranslation("alice").put("tier", "gold");
		TableSelector tier = new TableSelector("customerTier", table);

		MemoryContext ctx = new MemoryContext();
		from.extract(ctx, payload);
		tier.extract(ctx, payload);

		check("chain.extracted", "alice".equals(ctx.get("From.user")));
		check("chain.classified", "gold".equals(ctx.get("tier")));
	}

	private static void testJsonRoundTrip() throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		String json = mapper.writeValueAsString(tierSelector());
		check("json.has.type", json.contains("\"type\":\"table\""));
		check("json.no.attribute", !json.contains("\"attribute\""));

		Selector back = mapper.readValue(json, Selector.class);
		check("json.subtype", back instanceof TableSelector);

		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "alice");
		back.extract(ctx, null);
		check("json.roundtrip.works", "gold".equals(ctx.get("customerTier.tier")));
	}

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
