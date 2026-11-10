package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.ConfigFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Rules are keyed by id, so a cluster or server file can change or switch
/// off one rule; the earlier list form still loads.
class RuleSetFormTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String s) throws Exception {
		return MAPPER.readTree(s.replace('\'', '"'));
	}

	private static Crud crud(JsonNode tree) throws Exception {
		return MAPPER.treeToValue(tree, Crud.class);
	}

	@Test
	void keyedRulesTakeTheirIdsFromTheKeys() throws Exception {
		Crud c = crud(json("{'ruleSets':{'lab':{'rules':{'stamp':{'method':'INVITE'},'reject':{}}}}}"));
		RuleSet lab = c.getRuleSets().get("lab");
		assertEquals("lab", lab.getId());
		assertEquals(List.of("stamp", "reject"), List.copyOf(lab.getRules().keySet()));
		assertEquals("reject", lab.getRules().get("reject").getId());
	}

	@Test
	void theListFormStillLoads() throws Exception {
		Crud c = crud(json("{'ruleSets':{'lab':{'id':'lab','rules':[{'id':'stamp','method':'INVITE'},{'id':'reject'}]}}}"));
		assertEquals(List.of("stamp", "reject"), List.copyOf(c.getRuleSets().get("lab").getRules().keySet()));
		assertEquals("INVITE", c.getRuleSets().get("lab").getRules().get("stamp").getMethod());
	}

	@Test
	void twoRulesWithOneIdInAListBothLoad() throws Exception {
		Crud c = crud(json("{'ruleSets':{'lab':{'rules':[{'id':'r','method':'INVITE'},{'id':'r','method':'BYE'}]}}}"));
		assertEquals(List.of("r", "r-2"), List.copyOf(c.getRuleSets().get("lab").getRules().keySet()));
		assertEquals("BYE", c.getRuleSets().get("lab").getRules().get("r-2").getMethod());
	}

	@Test
	void itWritesTheKeyedFormWithoutIds() throws Exception {
		Crud c = crud(json("{'ruleSets':{'lab':{'id':'lab','rules':[{'id':'stamp','method':'INVITE'}]}}}"));
		assertEquals(json("{'ruleSets':{'lab':{'rules':{'stamp':{'method':'INVITE'}}}}}"),
				MAPPER.valueToTree(c).path("ruleSets").isMissingNode() ? null
						: MAPPER.createObjectNode().set("ruleSets", MAPPER.valueToTree(c).get("ruleSets")));
	}

	@Test
	void anOverlayChangesOneRuleAndSwitchesAnotherOff() throws Exception {
		JsonNode domain = json("{'ruleSets':{'lab':{'rules':{'stamp':{'method':'INVITE'},'reject':{'method':'INVITE'}}}}}");
		JsonNode server = json("{'ruleSets':{'lab':{'rules':{'stamp':{'method':'BYE'},'reject':{'enabled':false},'extra':{}}}}}");
		RuleSet lab = crud(ConfigFiles.merge(Arrays.asList(domain, server), MAPPER)).getRuleSets().get("lab");
		assertEquals(List.of("stamp", "reject", "extra"), List.copyOf(lab.getRules().keySet()));
		assertEquals("BYE", lab.getRules().get("stamp").getMethod());
		assertEquals("INVITE", lab.getRules().get("reject").getMethod());
		assertFalse(lab.getRules().get("reject").runs());
		assertTrue(lab.getRules().get("extra").runs());
	}
}
