package org.vorpal.blade.framework.v3.configuration.translations;

import java.util.LinkedList;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.vorpal.blade.framework.v3.configuration.translations.tables.HashTranslationTable;
import org.vorpal.blade.framework.v3.configuration.translations.tables.PrefixTranslationTable;

/// Verifies that the v3 [Translation] / [TranslationTable] classes
/// round-trip cleanly through Jackson, including the
/// `@JsonIdentityInfo`-driven JSON reference feature.
///
/// The critical scenario: a parent translation has two nested entries in
/// its `tables` list, and both entries are the **same** Java instance.
/// Jackson should emit the table fully on its first occurrence and emit
/// just a `{"id":"..."}` reference for the second. On read-back, both
/// nested slots in the deserialized tree should point to the same Java
/// instance.
///
/// Run with:
///
///     ./mvnw -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:libs/trie/target/classes:$(./mvnw -pl libs/framework dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout 2>/dev/null) \
///          org.vorpal.blade.framework.v3.configuration.translations.JsonRoundTripSmokeTest
public final class JsonRoundTripSmokeTest {
	private static int passed;
	private static int failed;

	/// Sample treatment payload — a network engineer's "what to do with
	/// this call" record. The class-level @JsonAutoDetect makes Jackson
	/// introspect its public fields without forcing the global mapper to
	/// expose every private field of every class on the type tree (which
	/// would crash on inherited HashMap internals under Java module rules).
	@JsonAutoDetect(fieldVisibility = Visibility.ANY)
	public static final class CarrierTreatment {
		public String carrier;
		public int costPerMinuteMicros;

		public CarrierTreatment() {
		}

		public CarrierTreatment(String carrier, int costPerMinuteMicros) {
			this.carrier = carrier;
			this.costPerMinuteMicros = costPerMinuteMicros;
		}
	}

	public static void main(String[] args) throws Exception {
		ObjectMapper mapper = new ObjectMapper()
				.enable(SerializationFeature.INDENT_OUTPUT);

		testHashTableRoundTrip(mapper);
		testPrefixTableRoundTrip(mapper);
		testIdentityReferenceFeature(mapper);
		testPrefixTableLookupAfterDeserialize(mapper);

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------

	/// Build a HashTranslationTable, serialize, deserialize, verify shape.
	private static void testHashTableRoundTrip(ObjectMapper mapper) throws Exception {
		HashTranslationTable<CarrierTreatment> table = new HashTranslationTable<>();
		table.setId("customers");
		table.setDescription("hash table of customers");

		Translation<CarrierTreatment> acme = table.createTranslation("acme");
		acme.setDescription("Acme Inc");
		acme.setTreatment(new CarrierTreatment("AvocadoTelegraph", 12000));

		Translation<CarrierTreatment> globex = table.createTranslation("globex");
		globex.setDescription("Globex Corp");
		globex.setTreatment(new CarrierTreatment("BananaTelecom", 8000));

		String json = mapper.writeValueAsString(table);
		System.out.println("--- HashTranslationTable JSON ---");
		System.out.println(json);

		check("hash.json.contains.id", json.contains("\"id\" : \"customers\""));
		check("hash.json.contains.description", json.contains("\"description\" : \"hash table of customers\""));
		check("hash.json.contains.translations", json.contains("\"translations\""));
		check("hash.json.contains.acme", json.contains("\"acme\""));
		check("hash.json.contains.globex", json.contains("\"globex\""));

		HashTranslationTable<CarrierTreatment> back = mapper.readValue(json,
				new TypeReference<HashTranslationTable<CarrierTreatment>>() {
				});

		check("hash.rt.id", "customers".equals(back.getId()));
		check("hash.rt.description", "hash table of customers".equals(back.getDescription()));
		check("hash.rt.size", back.size() == 2);
		check("hash.rt.acme.present", back.containsKey("acme"));
		check("hash.rt.acme.treatment.carrier",
				"AT&T".equals(back.get("acme").getTreatment().carrier));
		check("hash.rt.acme.treatment.cost",
				back.get("acme").getTreatment().costPerMinuteMicros == 12000);
		check("hash.rt.globex.treatment.carrier",
				"Verizon".equals(back.get("globex").getTreatment().carrier));
	}

	/// Same shape test for PrefixTranslationTable.
	private static void testPrefixTableRoundTrip(ObjectMapper mapper) throws Exception {
		PrefixTranslationTable<CarrierTreatment> table = new PrefixTranslationTable<>();
		table.setId("dial-plan");
		table.setDescription("US dial plan");

		Translation<CarrierTreatment> nanp = table.createTranslation("1");
		nanp.setTreatment(new CarrierTreatment("AT&T", 12000));

		Translation<CarrierTreatment> kc = table.createTranslation("1816");
		kc.setTreatment(new CarrierTreatment("Verizon", 8000));

		String json = mapper.writeValueAsString(table);
		System.out.println();
		System.out.println("--- PrefixTranslationTable JSON ---");
		System.out.println(json);

		check("prefix.json.contains.id", json.contains("\"id\" : \"dial-plan\""));
		check("prefix.json.contains.description", json.contains("\"description\" : \"US dial plan\""));
		check("prefix.json.contains.translations", json.contains("\"translations\""));
		check("prefix.json.contains.1816", json.contains("\"1816\""));

		PrefixTranslationTable<CarrierTreatment> back = mapper.readValue(json,
				new TypeReference<PrefixTranslationTable<CarrierTreatment>>() {
				});

		check("prefix.rt.id", "dial-plan".equals(back.getId()));
		check("prefix.rt.size", back.size() == 2);
		check("prefix.rt.1816.exact", back.containsKey("1816"));
		check("prefix.rt.lpo.kc.preserved",
				"Verizon".equals(back.longestPrefixOf("18165551234").getTreatment().carrier));
		check("prefix.rt.lpo.nanp.preserved",
				"AT&T".equals(back.longestPrefixOf("12125550000").getTreatment().carrier));
	}

	/// The crucial test: a Translation tree where the same TranslationTable
	/// instance appears in two places. The JSON must use a reference for
	/// the second occurrence, and on read-back both slots must resolve to
	/// the same Java instance.
	private static void testIdentityReferenceFeature(ObjectMapper mapper) throws Exception {
		// One shared "default routes" table referenced from two parents.
		HashTranslationTable<CarrierTreatment> shared = new HashTranslationTable<>();
		shared.setId("shared-defaults");
		shared.setDescription("Default fallback routes");
		shared.createTranslation("default")
				.setTreatment(new CarrierTreatment("FallbackCarrier", 99999));

		// Build two parent translations that both nest the SAME shared table.
		Translation<CarrierTreatment> parentA = new Translation<>("parent-a");
		parentA.setTables(new LinkedList<>());
		parentA.getTables().add(shared);

		Translation<CarrierTreatment> parentB = new Translation<>("parent-b");
		parentB.setTables(new LinkedList<>());
		parentB.getTables().add(shared);

		// Wrap both in an outer table so we have a single serialization root.
		HashTranslationTable<CarrierTreatment> outer = new HashTranslationTable<>();
		outer.setId("outer");
		outer.translations.put("a", parentA);
		outer.translations.put("b", parentB);

		String json = mapper.writeValueAsString(outer);
		System.out.println();
		System.out.println("--- Identity reference JSON (outer/parentA/parentB sharing shared) ---");
		System.out.println(json);

		// The id "shared-defaults" must appear ONCE as the full serialization,
		// and at least once more as a back-reference. Since the back-reference
		// renders as the bare id string (PropertyGenerator), the id will appear
		// at least twice in the document.
		int idOccurrences = countOccurrences(json, "\"shared-defaults\"");
		check("idref.shared.id.appears.twice.or.more", idOccurrences >= 2);

		// The full table body — recognizable by its description string —
		// should appear exactly ONCE.
		int bodyOccurrences = countOccurrences(json, "\"Default fallback routes\"");
		check("idref.shared.body.appears.once", bodyOccurrences == 1);

		// Round-trip back into a Java tree and verify both nested slots
		// point to the SAME Java instance, not copies.
		HashTranslationTable<CarrierTreatment> backOuter = mapper.readValue(json,
				new TypeReference<HashTranslationTable<CarrierTreatment>>() {
				});

		Translation<CarrierTreatment> backA = backOuter.get("a");
		Translation<CarrierTreatment> backB = backOuter.get("b");
		check("idref.rt.parentA.present", backA != null);
		check("idref.rt.parentB.present", backB != null);
		check("idref.rt.parentA.tables.size", backA.getTables() != null && backA.getTables().size() == 1);
		check("idref.rt.parentB.tables.size", backB.getTables() != null && backB.getTables().size() == 1);

		TranslationTable<CarrierTreatment> backSharedFromA = backA.getTables().get(0);
		TranslationTable<CarrierTreatment> backSharedFromB = backB.getTables().get(0);

		// THE assertion the user cared about: same Java instance.
		check("idref.rt.same.identity", backSharedFromA == backSharedFromB);
		check("idref.rt.same.id", "shared-defaults".equals(backSharedFromA.getId()));
		check("idref.rt.same.size", backSharedFromA.size() == 1);
		check("idref.rt.same.contents",
				backSharedFromA.get("default") != null &&
				"FallbackCarrier".equals(
						backSharedFromA.get("default").getTreatment().carrier));
	}

	/// After a PrefixTranslationTable round-trips through Jackson, the
	/// trie inside the deserialized instance must still be functional —
	/// confirming the @JsonProperty setter actually populated the trie
	/// (not just an unrelated map).
	private static void testPrefixTableLookupAfterDeserialize(ObjectMapper mapper) throws Exception {
		PrefixTranslationTable<CarrierTreatment> table = new PrefixTranslationTable<>();
		table.setId("intl-dial-plan");
		table.createTranslation("011").setTreatment(new CarrierTreatment("IDT", 120000));
		table.createTranslation("01144").setTreatment(new CarrierTreatment("BT", 90000));
		table.createTranslation("01133").setTreatment(new CarrierTreatment("Orange", 95000));

		String json = mapper.writeValueAsString(table);
		PrefixTranslationTable<CarrierTreatment> back = mapper.readValue(json,
				new TypeReference<PrefixTranslationTable<CarrierTreatment>>() {
				});

		check("prefixrt.lpo.uk.specific",
				"BT".equals(back.longestPrefixOf("0114420755512345").getTreatment().carrier));
		check("prefixrt.lpo.fr.specific",
				"Orange".equals(back.longestPrefixOf("0113312345678").getTreatment().carrier));
		check("prefixrt.lpo.intl.fallback",
				"IDT".equals(back.longestPrefixOf("011491234567890").getTreatment().carrier));
		check("prefixrt.lpo.noMatch", back.longestPrefixOf("442075550000") == null);
	}

	// ------------------------------------------------------------------

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) != -1) {
			count++;
			idx += needle.length();
		}
		return count;
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
