package org.vorpal.blade.framework.v3.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;
import org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;
import org.vorpal.blade.framework.v3.irouter.IRouterConfigSample;

/// Guards the deliberate absence of `@JsonIdentityInfo` on the v3 config model.
///
/// `Selector` and `Connector` both carried it until 2026-08-01. It promotes
/// `id` to a **document-global** Jackson object key, which made two ordinary
/// authoring shapes fail the *entire* configuration:
///
/// - omitting `id` — "No Object Id found for an instance of …" (one omission
///   anywhere was enough; neither class declared `id` required)
/// - reusing an `id` — "Already had POJO for id" (across states, and across
///   different subclasses)
///
/// Neither was survivable in a useful way. `Settings.reload()` swallows the
/// exception, so a running node keeps serving on its last-good config and the
/// publish looks successful; a **cold start** leaves `config` null and the
/// router answers 500 on every initial request. The failure therefore surfaced
/// at the next restart or cluster scale-up, disconnected from the edit that
/// caused it.
///
/// If any test here starts failing with an object-id message, someone has
/// restored the annotation and that latent failure is back. Fix the annotation,
/// not the test. (v2's config classes keep theirs and genuinely need it — a v2
/// config declares selectors once and references them by bare-string id.)
class ObjectIdFreeConfigTest {

	/// Configured the way the engine's SettingsManager configures its own.
	private static ObjectMapper engineMapper() {
		ObjectMapper m = new ObjectMapper();
		m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return m;
	}

	@Nested
	@DisplayName("Connector (iRouter pipeline)")
	class Connectors {

		@Test
		@DisplayName("a connector with no id loads")
		void connectorWithoutIdLoads() throws Exception {
			// `id` is only a log label (Connector#tag) and nothing declares it
			// required, so a hand-written pipeline omits it easily.
			IRouterConfig cfg = engineMapper().readValue(
					"{\"pipeline\":[{\"type\":\"map\"}]}", IRouterConfig.class);

			assertEquals(1, cfg.getPipeline().size());
		}

		@Test
		@DisplayName("a whole pipeline with no ids loads")
		void severalConnectorsWithoutIdsLoad() throws Exception {
			IRouterConfig cfg = engineMapper().readValue(
					"{\"pipeline\":[{\"type\":\"map\"},{\"type\":\"table\"},{\"type\":\"sip\"}]}",
					IRouterConfig.class);

			assertEquals(3, cfg.getPipeline().size());
		}

		@Test
		@DisplayName("two connectors may share an id and stay distinct")
		void duplicateConnectorIdsStayDistinct() throws Exception {
			IRouterConfig cfg = engineMapper().readValue(
					"{\"pipeline\":["
							+ "{\"type\":\"map\",\"id\":\"lookup\",\"description\":\"first\"},"
							+ "{\"type\":\"map\",\"id\":\"lookup\",\"description\":\"second\"}]}",
					IRouterConfig.class);

			List<Connector> pipeline = cfg.getPipeline();
			assertEquals(2, pipeline.size(), "both stages must survive");
			assertNotSame(pipeline.get(0), pipeline.get(1),
					"they must not collapse into one shared object");
			assertEquals("first", pipeline.get(0).getDescription());
			assertEquals("second", pipeline.get(1).getDescription());
		}

		@Test
		@DisplayName("a shared id across different connector types loads")
		void duplicateIdAcrossSubclassesLoads() throws Exception {
			IRouterConfig cfg = engineMapper().readValue(
					"{\"pipeline\":[{\"type\":\"map\",\"id\":\"x\"},{\"type\":\"table\",\"id\":\"x\"}]}",
					IRouterConfig.class);

			assertEquals(2, cfg.getPipeline().size());
			assertNotSame(cfg.getPipeline().get(0), cfg.getPipeline().get(1));
		}
	}

	@Nested
	@DisplayName("Selector (FSMAR states, iRouter connectors)")
	class Selectors {

		@Test
		@DisplayName("selectors with no id load")
		void selectorsWithoutIdsLoad() throws Exception {
			// admin/flow only *warns* about a missing selector id ("the
			// extracted value has no variable name") — which was wrong while
			// the annotation was present, since it was fatal.
			AppRouterConfiguration cfg = engineMapper().readValue(
					"{\"version\":3,\"states\":{\"null\":{\"selectors\":["
							+ "{\"attribute\":\"From\"},{\"attribute\":\"To\"}],\"triggers\":{}}}}",
					AppRouterConfiguration.class);

			assertEquals(2, cfg.getStates().get("null").getSelectors().size());
		}

		@Test
		@DisplayName("a selector id reused in another state stays distinct")
		void duplicateSelectorIdsAcrossStatesStayDistinct() throws Exception {
			AppRouterConfiguration cfg = engineMapper().readValue(
					"{\"version\":3,\"states\":{"
							+ "\"null\":{\"selectors\":[{\"id\":\"From\",\"attribute\":\"From\"}],"
							+ "        \"triggers\":{}},"
							+ "\"screening\":{\"selectors\":[{\"id\":\"From\",\"attribute\":\"To\"}],"
							+ "        \"triggers\":{}}}}",
					AppRouterConfiguration.class);

			Selector first = cfg.getStates().get("null").getSelectors().get(0);
			Selector second = cfg.getStates().get("screening").getSelectors().get(0);
			assertNotSame(first, second, "they must not collapse into one shared object");
			assertEquals("From", first.getAttribute());
			assertEquals("To", second.getAttribute());
		}

		@Test
		@DisplayName("the same id twice in one state stays two selectors")
		void duplicateSelectorIdsInOneStateStayDistinct() throws Exception {
			AppRouterConfiguration cfg = engineMapper().readValue(
					"{\"version\":3,\"states\":{\"null\":{\"selectors\":["
							+ "{\"id\":\"X\",\"attribute\":\"From\"},"
							+ "{\"id\":\"X\",\"attribute\":\"To\"}],\"triggers\":{}}}}",
					AppRouterConfiguration.class);

			List<Selector> sels = cfg.getStates().get("null").getSelectors();
			assertEquals(2, sels.size());
			assertNotSame(sels.get(0), sels.get(1));
			// Both run; the later one wins on the context variable.
			assertEquals("From", sels.get(0).getAttribute());
			assertEquals("To", sels.get(1).getAttribute());
		}
	}

	@Nested
	@DisplayName("generated JSON Schema")
	class Schema {

		/// The Configurator renders its forms from `_schemas/*.jschema`, so the
		/// annotation removal has to leave that shape alone. Measured before and
		/// after the change: byte-identical output, because victools never
		/// modelled the object-id alternative in the first place. These assert
		/// the properties that made it safe, so a future generator upgrade that
		/// *did* start emitting a bare-string alternative would be caught.
		@Test
		@DisplayName("selectors and connectors are objects, never id references")
		void noBareStringAlternative() {
			for (Class<?> clazz : new Class<?>[] { AppRouterConfiguration.class, IRouterConfig.class }) {
				JsonNode schema = SettingsManager.generateSchemaNode(clazz, new ObjectMapper());
				JsonNode defs = schema.path("$defs");
				assertTrue(defs.size() > 0, () -> clazz.getSimpleName() + " produced no $defs");

				defs.fields().forEachRemaining(e -> {
					String name = e.getKey();
					if (!name.contains("Selector") && !name.contains("Connector")) {
						return;
					}
					String type = e.getValue().path("type").asText("");
					assertEquals("object", type,
							() -> name + " should be an object, got '" + type + "' — a string"
									+ " alternative means object-id referencing crept back in");
				});
			}
		}

		@Test
		@DisplayName("no object-id machinery leaks into the schema")
		void noObjectIdArtifacts() {
			String schema = SettingsManager
					.generateSchemaNode(AppRouterConfiguration.class, new ObjectMapper()).toString();

			assertFalse(schema.contains("objectId"),
					"object-id metadata should not appear in the generated schema");
		}
	}

	@Nested
	@DisplayName("shipped samples")
	class Samples {

		@Test
		@DisplayName("IRouterConfigSample round-trips byte-stably and emits no id references")
		void iRouterSampleIsStable() throws Exception {
			// The generated _samples/irouter.json.SAMPLE goes through model
			// serialization, so removing the annotation must not change its
			// shape on a live server.
			ObjectMapper m = engineMapper();
			String json = m.writerWithDefaultPrettyPrinter()
					.writeValueAsString(new IRouterConfigSample());
			String again = m.writerWithDefaultPrettyPrinter()
					.writeValueAsString(m.readValue(json, IRouterConfig.class));

			assertEquals(json, again, "sample serialization is not stable across a round trip");
			assertFalse(json.matches("(?s).*\"pipeline\"\\s*:\\s*\\[\\s*\".*"),
					"pipeline must hold objects, never bare-string id references");
			assertFalse(json.matches("(?s).*\"selectors\"\\s*:\\s*\\[\\s*\".*"),
					"selectors must hold objects, never bare-string id references");
		}

		@Test
		@DisplayName("the sample still loads")
		void iRouterSampleLoads() throws Exception {
			ObjectMapper m = engineMapper();
			IRouterConfig cfg = m.readValue(
					m.writeValueAsString(new IRouterConfigSample()), IRouterConfig.class);

			assertTrue(cfg.getPipeline().size() >= 4,
					() -> "unexpected sample pipeline: " + cfg.getPipeline().size());
		}
	}
}
