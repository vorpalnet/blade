package org.vorpal.blade.admin.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// [AppCrudSection]: which applications
/// are listed, how the section goes back into the file, and that the section
/// schema has the shape the page's form resolver reads.
class AppCrudSectionTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path dir;

	@Test
	void listsOnlyAppsWhoseSchemaHasACrudSection() throws Exception {
		Files.writeString(dir.resolve("transfer.jschema"), "{\"properties\":{\"logging\":{},\"crud\":{}}}");
		Files.writeString(dir.resolve("irouter.jschema"), "{\"properties\":{\"crud\":{\"$ref\":\"#/$defs/Crud\"}}}");
		Files.writeString(dir.resolve("hold.jschema"), "{\"properties\":{\"logging\":{}}}");
		Files.writeString(dir.resolve("crud.jschema"), "{\"properties\":{\"crud\":{}}}");
		Files.writeString(dir.resolve("broken.jschema"), "{not json");
		assertEquals(List.of("irouter", "transfer"), new ArrayList<>(AppCrudSection.appsWithCrud(dir)));
	}

	@Test
	void noSchemaDirectoryListsNothing() throws Exception {
		assertTrue(AppCrudSection.appsWithCrud(dir.resolve("missing")).isEmpty());
	}

	@Test
	void spliceReplacesTheSectionInPlaceAndKeepsTheRest() throws Exception {
		ObjectNode doc = (ObjectNode) MAPPER.readTree(
				"{\"logging\":{\"level\":\"FINE\"},\"crud\":{\"defaultRuleSet\":\"old\"},\"style\":\"blind\"}");
		ObjectNode section = (ObjectNode) MAPPER.readTree(
				"{\"defaultRuleSet\":\"new\",\"ruleSets\":{\"new\":{\"id\":\"new\",\"rules\":[]}}}");
		AppCrudSection.splice(doc, section);
		assertEquals(List.of("logging", "crud", "style"), fieldNames(doc));
		assertEquals("new", doc.path("crud").path("defaultRuleSet").asText());
		assertEquals("FINE", doc.path("logging").path("level").asText());
	}

	@Test
	void anEmptySectionRemovesTheKey() throws Exception {
		ObjectNode doc = (ObjectNode) MAPPER.readTree("{\"logging\":{},\"crud\":{\"defaultRuleSet\":\"x\"}}");
		AppCrudSection.splice(doc, (ObjectNode) MAPPER.readTree("{\"ruleSets\":{},\"pipeline\":[]}"));
		assertFalse(doc.has("crud"));
	}

	@Test
	void sectionSchemaHasTheShapeThePageReads() {
		JsonNode schema = AppCrudSection.schema();
		assertTrue(schema.path("properties").path("pipeline").path("items").path("anyOf").isArray(),
				"properties.pipeline.items.anyOf");
		assertTrue(schema.path("$defs").path("Rule").path("properties").path("operations")
				.path("items").path("anyOf").isArray(), "$defs.Rule.properties.operations.items.anyOf");
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
