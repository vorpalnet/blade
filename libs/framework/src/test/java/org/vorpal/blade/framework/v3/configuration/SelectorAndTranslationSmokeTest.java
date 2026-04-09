package org.vorpal.blade.framework.v3.configuration;

import java.util.LinkedList;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import org.vorpal.blade.framework.v2.config.RegExRoute;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;
import org.vorpal.blade.framework.v3.configuration.translations.tables.HashTranslationTable;
import org.vorpal.blade.framework.v3.configuration.translations.tables.PrefixTranslationTable;

/// Test harness for [Selector] key extraction and [TranslationTable] lookups
/// using the v2 [DummyRequest] to simulate SIP messages outside WebLogic.
///
/// Run with:
///
///     ./mvnw -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:libs/trie/target/classes:$(./mvnw -pl libs/framework dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout 2>/dev/null) \
///          org.vorpal.blade.framework.v3.configuration.SelectorAndTranslationSmokeTest
public final class SelectorAndTranslationSmokeTest {
	private static int passed;
	private static int failed;
	private static ObjectMapper mapper;
	private static SchemaGenerator schemaGenerator;

	/// Sample treatment payload for a call routing scenario.
	@JsonAutoDetect(fieldVisibility = Visibility.ANY)
	public static final class RouteTreatment {
		@JsonPropertyDescription("SIP URI of the recording media server")
		public String mediaServer;
		@JsonPropertyDescription("Recording codec preference")
		public String codec;
		@JsonPropertyDescription("Maximum recording duration in seconds")
		public int maxDurationSecs;

		public RouteTreatment() {}

		public RouteTreatment(String mediaServer, String codec, int maxDurationSecs) {
			this.mediaServer = mediaServer;
			this.codec = codec;
			this.maxDurationSecs = maxDurationSecs;
		}
	}

	/// Sample top-level config that combines a Selector with TranslationTables.
	@JsonAutoDetect(fieldVisibility = Visibility.ANY)
	public static final class SampleServiceConfig {
		@JsonPropertyDescription("Selector for extracting the routing key from inbound SIP requests")
		public Selector selector;
		@JsonPropertyDescription("Translation tables for route lookups")
		public LinkedList<TranslationTable<RouteTreatment>> tables = new LinkedList<>();

		public SampleServiceConfig() {}
	}

	public static void main(String[] args) throws Exception {
		// Initialize a Logger so Selector.findKey() doesn't NPE outside WebLogic
		Logger logger = new Logger("SmokeTest", null) {};
		logger.setParent(java.util.logging.Logger.getLogger(""));
		SettingsManager.setSipLogger(logger);

		// Shared ObjectMapper and SchemaGenerator
		mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		JacksonModule jacksonModule = new JacksonModule(
				JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
				JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
				JacksonOption.RESPECT_JSONPROPERTY_ORDER,
				JacksonOption.IGNORE_PROPERTY_NAMING_STRATEGY);
		SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
				SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
				.with(jacksonModule)
				.with(Option.DEFINITIONS_FOR_ALL_OBJECTS,
						Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES);
		SchemaGeneratorConfig sgConfig = configBuilder.build();
		schemaGenerator = new SchemaGenerator(sgConfig);

		testSelectorExtractsFromHeader();
		testSelectorExtractsFromFromHeader();
		testSelectorExtractsNamedGroups();
		testSelectorNoMatch();
		testSelectorNullRequest();
		testHashTableLookupWithSelector();
		testPrefixTableLookupWithSelector();
		testSelectorWithMultipleHeaders();
		testEndToEndRouting();

		// Print sample configs and schemas for Configurator testing
		printSampleConfigs();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------
	// Selector tests
	// ------------------------------------------------------------------

	/// Basic test: extract the user part from a To header using a SIP URI regex.
	private static void testSelectorExtractsFromHeader() throws Exception {
		Selector selector = new Selector("to-user", "To",
				"(?:\"(?<name>.*)\"\\s*)?[<]*(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		SipServletRequest request = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");

		RegExRoute route = selector.findKey(request);
		check("selector.to.user.notNull", route != null);
		check("selector.to.user.value", route != null && "bob".equals(route.key));
	}

	/// Extract from the From header (DummyRequest doesn't populate requestUri
	/// from string constructors, so we test a different header instead of Request-URI).
	private static void testSelectorExtractsFromFromHeader() throws Exception {
		Selector selector = new Selector("from-user", "From",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		SipServletRequest request = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");

		RegExRoute route = selector.findKey(request);
		check("selector.from.user.notNull", route != null);
		check("selector.from.user.value", route != null && "alice".equals(route.key));
	}

	/// Verify named capturing groups are populated in the RegExRoute attributes.
	private static void testSelectorExtractsNamedGroups() throws Exception {
		Selector selector = new Selector("to-parts", "To",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		SipServletRequest request = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");

		RegExRoute route = selector.findKey(request);
		check("selector.groups.notNull", route != null);
		check("selector.groups.user", route != null && "bob".equals(route.attributes.get("user")));
		check("selector.groups.host", route != null && "biloxi.example.com".equals(route.attributes.get("host")));
	}

	/// Selector returns null when the pattern doesn't match.
	private static void testSelectorNoMatch() throws Exception {
		Selector selector = new Selector("digits-only", "To",
				"^(\\d+)$", "$1");

		SipServletRequest request = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");

		RegExRoute route = selector.findKey(request);
		check("selector.noMatch.null", route == null);
	}

	/// Selector handles null request gracefully.
	private static void testSelectorNullRequest() throws Exception {
		Selector selector = new Selector("any", "To", ".*", "$0");

		RegExRoute route = selector.findKey(null);
		check("selector.nullRequest.null", route == null);
	}

	// ------------------------------------------------------------------
	// Selector + TranslationTable integration
	// ------------------------------------------------------------------

	/// Use a Selector to extract a key, then look it up in a HashTranslationTable.
	private static void testHashTableLookupWithSelector() throws Exception {
		// Build a selector that extracts the dialed number (user part of To)
		Selector selector = new Selector("to-user", "To",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		// Build a hash table mapping usernames to treatments
		HashTranslationTable<String> table = new HashTranslationTable<>();
		table.setId("user-routes");
		table.createTranslation("bob").setTreatment("route-to-recording-A");
		table.createTranslation("carol").setTreatment("route-to-recording-B");

		// Simulate a request to bob
		SipServletRequest request = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");

		RegExRoute route = selector.findKey(request);
		check("hash.selector.keyFound", route != null);

		Translation<String> translation = table.get(route.key);
		check("hash.lookup.found", translation != null);
		check("hash.lookup.treatment", translation != null && "route-to-recording-A".equals(translation.getTreatment()));

		// Key not in table
		SipServletRequest request2 = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:dave@biloxi.example.com");
		RegExRoute route2 = selector.findKey(request2);
		check("hash.miss.keyFound", route2 != null);
		check("hash.miss.noTranslation", table.get(route2.key) == null);
	}

	/// Use a Selector to extract a phone number prefix, then look it up
	/// in a PrefixTranslationTable (longest-prefix match).
	private static void testPrefixTableLookupWithSelector() throws Exception {
		// Selector extracts the user part (phone number) from the To header
		Selector selector = new Selector("to-number", "To",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		// Prefix table: telco dial plan
		PrefixTranslationTable<String> table = new PrefixTranslationTable<>();
		table.setId("dial-plan");
		table.createTranslation("1").setTreatment("domestic-default");
		table.createTranslation("1816").setTreatment("kansas-city");
		table.createTranslation("1212").setTreatment("new-york");
		table.createTranslation("44").setTreatment("united-kingdom");

		// Call to Kansas City number
		SipServletRequest request = new DummyRequest("INVITE",
				"sip:operator@switch.example.com", "sip:18165551234@gateway.example.com");
		RegExRoute route = selector.findKey(request);
		check("prefix.kc.keyFound", route != null);
		Translation<String> t = table.longestPrefixOf(route.key);
		check("prefix.kc.found", t != null);
		check("prefix.kc.treatment", t != null && "kansas-city".equals(t.getTreatment()));

		// Call to a 1-xxx number that isn't KC or NY — falls back to "1"
		SipServletRequest request2 = new DummyRequest("INVITE",
				"sip:operator@switch.example.com", "sip:15035551234@gateway.example.com");
		RegExRoute route2 = selector.findKey(request2);
		Translation<String> t2 = table.longestPrefixOf(route2.key);
		check("prefix.fallback.found", t2 != null);
		check("prefix.fallback.treatment", t2 != null && "domestic-default".equals(t2.getTreatment()));

		// International call — UK
		SipServletRequest request3 = new DummyRequest("INVITE",
				"sip:operator@switch.example.com", "sip:442075551234@gateway.example.com");
		RegExRoute route3 = selector.findKey(request3);
		Translation<String> t3 = table.longestPrefixOf(route3.key);
		check("prefix.uk.found", t3 != null);
		check("prefix.uk.treatment", t3 != null && "united-kingdom".equals(t3.getTreatment()));

		// No match at all
		SipServletRequest request4 = new DummyRequest("INVITE",
				"sip:operator@switch.example.com", "sip:9995551234@gateway.example.com");
		RegExRoute route4 = selector.findKey(request4);
		Translation<String> t4 = table.longestPrefixOf(route4.key);
		check("prefix.noMatch.null", t4 == null);
	}

	/// Test selector against custom SIP headers (e.g. X-Cisco-Gucid).
	private static void testSelectorWithMultipleHeaders() throws Exception {
		Selector selector = new Selector("gucid", "X-Cisco-Gucid", "(?<gucid>.*)", "${gucid}");

		DummyRequest request = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");
		request.setHeader("X-Cisco-Gucid", "ABC123DEF456");

		RegExRoute route = selector.findKey(request);
		check("header.custom.notNull", route != null);
		check("header.custom.value", route != null && "ABC123DEF456".equals(route.key));

		// Missing header — selector returns null
		DummyRequest request2 = new DummyRequest("INVITE",
				"sip:alice@atlanta.example.com", "sip:bob@biloxi.example.com");
		RegExRoute route2 = selector.findKey(request2);
		check("header.missing.null", route2 == null);
	}

	/// End-to-end: Selector extracts key from request → HashTable lookup →
	/// Translation has nested PrefixTable → second-level prefix lookup.
	private static void testEndToEndRouting() throws Exception {
		// Top-level selector: extract the called number
		Selector selector = new Selector("to-user", "To",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		// Top-level hash table: customer ID → Translation with nested prefix table
		HashTranslationTable<String> customers = new HashTranslationTable<>();
		customers.setId("customers");

		// Customer "acme" has a nested dial plan
		Translation<String> acme = customers.createTranslation("acme");
		acme.setDescription("Acme Corp dial plan");

		PrefixTranslationTable<String> acmeDialPlan = new PrefixTranslationTable<>();
		acmeDialPlan.setId("acme-dial-plan");
		acmeDialPlan.createTranslation("1800").setTreatment("toll-free-carrier");
		acmeDialPlan.createTranslation("1").setTreatment("domestic-carrier");

		acme.setTables(new java.util.LinkedList<>());
		acme.getTables().add(acmeDialPlan);

		// Simulate: request arrives, selector extracts "acme"
		SipServletRequest request = new DummyRequest("INVITE",
				"sip:caller@pbx.example.com", "sip:acme@gateway.example.com");
		RegExRoute route = selector.findKey(request);
		check("e2e.selector.key", route != null && "acme".equals(route.key));

		// Look up customer in top-level table
		Translation<String> customerTranslation = customers.get(route.key);
		check("e2e.customer.found", customerTranslation != null);

		// Drill into nested prefix table with the actual dialed number
		// (in a real app this would come from a second selector or the INVITE body)
		TranslationTable<String> nestedTable = customerTranslation.getTables().get(0);
		check("e2e.nested.isPrefixTable", nestedTable instanceof PrefixTranslationTable);

		PrefixTranslationTable<String> dialPlan = (PrefixTranslationTable<String>) nestedTable;
		Translation<String> tollFree = dialPlan.longestPrefixOf("18005551234");
		check("e2e.tollFree.found", tollFree != null);
		check("e2e.tollFree.treatment", tollFree != null && "toll-free-carrier".equals(tollFree.getTreatment()));

		Translation<String> domestic = dialPlan.longestPrefixOf("12125551234");
		check("e2e.domestic.found", domestic != null);
		check("e2e.domestic.treatment", domestic != null && "domestic-carrier".equals(domestic.getTreatment()));
	}

	// ------------------------------------------------------------------
	// Sample config + schema output for Configurator testing
	// ------------------------------------------------------------------

	private static void printSampleConfigs() throws Exception {
		System.out.println();
		System.out.println("================================================================");
		System.out.println("  SAMPLE CONFIGURATIONS & SCHEMAS FOR CONFIGURATOR TESTING");
		System.out.println("================================================================");

		// --- 1. Simple HashTranslationTable config ---
		printSection("1. HashTranslationTable with RouteTreatment");

		HashTranslationTable<RouteTreatment> hashTable = new HashTranslationTable<>();
		hashTable.setId("recording-routes");
		hashTable.setDescription("Maps call centers to recording media servers");

		Translation<RouteTreatment> nyc = hashTable.createTranslation("nyc-callcenter");
		nyc.setDescription("New York call center");
		nyc.setTreatment(new RouteTreatment("sip:recorder@nyc-media.example.com", "PCMU", 3600));

		Translation<RouteTreatment> chi = hashTable.createTranslation("chi-callcenter");
		chi.setDescription("Chicago call center");
		chi.setTreatment(new RouteTreatment("sip:recorder@chi-media.example.com", "G722", 7200));

		printJson("Sample Config", hashTable);

		// --- 2. PrefixTranslationTable config (dial plan) ---
		printSection("2. PrefixTranslationTable with RouteTreatment (dial plan)");

		PrefixTranslationTable<RouteTreatment> prefixTable = new PrefixTranslationTable<>();
		prefixTable.setId("dial-plan");
		prefixTable.setDescription("Route calls by dialed number prefix");

		prefixTable.createTranslation("1")
				.setTreatment(new RouteTreatment("sip:domestic@carrier.example.com", "PCMU", 3600));
		prefixTable.createTranslation("1800")
				.setTreatment(new RouteTreatment("sip:tollfree@carrier.example.com", "PCMU", 1800));
		prefixTable.createTranslation("44")
				.setTreatment(new RouteTreatment("sip:uk@intl-carrier.example.com", "G729", 7200));
		prefixTable.createTranslation("91")
				.setTreatment(new RouteTreatment("sip:india@intl-carrier.example.com", "G729", 7200));

		printJson("Sample Config", prefixTable);

		// --- 3. Full service config with Selector + nested tables ---
		printSection("3. Full SampleServiceConfig (Selector + mixed tables)");

		SampleServiceConfig config = new SampleServiceConfig();
		config.selector = new Selector("to-user", "To",
				"(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
				"${user}");

		// Hash table for customer lookup
		HashTranslationTable<RouteTreatment> customers = new HashTranslationTable<>();
		customers.setId("customers");
		customers.setDescription("Customer-specific routing");

		Translation<RouteTreatment> acme = customers.createTranslation("acme");
		acme.setDescription("Acme Corp");
		acme.setTreatment(new RouteTreatment("sip:acme-rec@media.example.com", "PCMU", 3600));

		// Acme has a nested prefix table for per-region routing
		PrefixTranslationTable<RouteTreatment> acmeRegions = new PrefixTranslationTable<>();
		acmeRegions.setId("acme-regions");
		acmeRegions.setDescription("Acme per-region dial plan");
		acmeRegions.createTranslation("1212")
				.setTreatment(new RouteTreatment("sip:acme-nyc@media.example.com", "PCMU", 3600));
		acmeRegions.createTranslation("1312")
				.setTreatment(new RouteTreatment("sip:acme-chi@media.example.com", "G722", 3600));
		acmeRegions.createTranslation("1")
				.setTreatment(new RouteTreatment("sip:acme-default@media.example.com", "PCMU", 1800));

		acme.setTables(new LinkedList<>());
		acme.getTables().add(acmeRegions);

		Translation<RouteTreatment> globex = customers.createTranslation("globex");
		globex.setDescription("Globex Corp");
		globex.setTreatment(new RouteTreatment("sip:globex-rec@media.example.com", "G729", 7200));

		config.tables.add(customers);

		printJson("Sample Config", config);
		printSchema("Schema", SampleServiceConfig.class);

		// --- 4. Selector by itself ---
		printSection("4. Selector schema");
		printSchema("Schema", Selector.class);
	}

	private static void printSection(String title) {
		System.out.println();
		System.out.println("--- " + title + " ---");
	}

	private static void printJson(String label, Object obj) throws Exception {
		System.out.println();
		System.out.println(label + ":");
		System.out.println(mapper.writeValueAsString(obj));
	}

	private static void printSchema(String label, Class<?> clazz) throws Exception {
		JsonNode schema = schemaGenerator.generateSchema(clazz);
		System.out.println();
		System.out.println(label + ":");
		System.out.println(mapper.writeValueAsString(schema));
	}

	// ------------------------------------------------------------------

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
