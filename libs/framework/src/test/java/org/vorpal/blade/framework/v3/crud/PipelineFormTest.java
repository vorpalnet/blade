package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.ConfigFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The pipeline is keyed by connector id like rules, and `variables` merge by
/// name, so a cluster or server file can change one of either.
class PipelineFormTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String s) throws Exception {
		return MAPPER.readTree(s.replace('\'', '"'));
	}

	@Test
	void theListFormLoadsAndTheKeyedFormIsWritten() throws Exception {
		Crud c = MAPPER.treeToValue(json("{'pipeline':[{'type':'sip','id':'sip'},{'type':'table','id':'pick'}]}"), Crud.class);
		assertEquals(List.of("sip", "pick"), List.copyOf(c.getPipeline().keySet()));
		assertEquals("pick", c.getPipeline().get("pick").getId());
		JsonNode written = MAPPER.valueToTree(c).get("pipeline");
		java.util.List<String> names = new java.util.ArrayList<>();
		written.fieldNames().forEachRemaining(names::add);
		assertEquals(List.of("sip", "pick"), names);
		assertFalse(written.get("sip").has("id"));
		assertEquals("sip", written.get("sip").get("type").asText());
	}

	@Test
	void twoConnectorsWithOneIdBothLoad() throws Exception {
		Crud c = MAPPER.treeToValue(json("{'pipeline':[{'type':'sip','id':'a'},{'type':'sip','id':'a'}]}"), Crud.class);
		assertEquals(List.of("a", "a-2"), List.copyOf(c.getPipeline().keySet()));
	}

	@Test
	void anOverlaySetsOneVariableAndAddsAConnector() throws Exception {
		JsonNode domain = json("{'variables':{'carrier':'sbc1','tier':'gold'},'pipeline':{'sip':{'type':'sip'}}}");
		JsonNode server = json("{'variables':{'carrier':'sbc2'},'pipeline':{'extra':{'type':'sip'}}}");
		Crud c = MAPPER.treeToValue(ConfigFiles.merge(Arrays.asList(domain, server), MAPPER), Crud.class);
		assertEquals("sbc2", c.getVariables().get("carrier"));
		assertEquals("gold", c.getVariables().get("tier"));
		assertEquals(List.of("sip", "extra"), List.copyOf(c.getPipeline().keySet()));
	}
}
