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
	void aSectionThatSwitchesTheDefaultOffIsKept() throws Exception {
		ObjectNode doc = (ObjectNode) MAPPER.readTree("{}");
		AppCrudSection.splice(doc, (ObjectNode) MAPPER.readTree("{\"defaultRuleSet\":null}"));
		org.junit.jupiter.api.Assertions.assertTrue(doc.path("crud").has("defaultRuleSet"));
	}

	@Test
	void sectionSchemaHasTheShapeThePageReads() {
		JsonNode schema = AppCrudSection.schema();
		// The pipeline is keyed: a map whose value type is the anyOf of connectors.
		String map = schema.path("properties").path("pipeline").path("$ref").asText().replace("#/$defs/", "");
		String value = schema.path("$defs").path(map).path("additionalProperties").path("$ref").asText().replace("#/$defs/", "");
		assertTrue(schema.path("$defs").path(value).path("anyOf").isArray(), "pipeline values: anyOf of connectors");
		assertTrue(schema.path("$defs").path("Rule").path("properties").path("operations")
				.path("items").path("anyOf").isArray(), "$defs.Rule.properties.operations.items.anyOf");
	}

	@Test
	void anAppsOwnSchemaIsUsedWhenItHasOne(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		// An app on an older framework: rules as a list, no variables.
		java.nio.file.Files.write(dir.resolve("old.jschema"), ("{\"$defs\":{\"Crud\":{\"type\":\"object\",\"properties\":"
				+ "{\"pipeline\":{\"type\":\"array\"},\"ruleSets\":{}}},\"RuleSet\":{\"properties\":{\"rules\":{\"type\":\"array\"}}}},"
				+ "\"properties\":{\"crud\":{\"$ref\":\"#/$defs/Crud\"}}}").getBytes());
		JsonNode schema = AppCrudSection.schema(dir, "old");
		assertEquals("array", schema.path("properties").path("pipeline").path("type").asText());
		assertFalse(schema.path("properties").has("variables"));
		assertEquals("array", schema.path("$defs").path("RuleSet").path("properties").path("rules").path("type").asText());
		// No file: this server's own.
		assertTrue(AppCrudSection.schema(dir, "missing").path("properties").has("variables"));
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
