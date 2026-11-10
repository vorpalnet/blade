package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The choice filters are written, read back and offered in the schema:
/// reading them as text must not drop them anywhere.
class RuleChoicesRoundTripTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void theyAreWrittenAndReadBack() throws Exception {
		Rule r = new Rule();
		r.setLeg(Rule.Leg.callee);
		r.setSdp(Rule.SdpRole.offer);
		r.setMessageType(Rule.MessageType.request);
		JsonNode written = MAPPER.valueToTree(r);
		assertEquals("callee", written.path("leg").asText());
		assertEquals("offer", written.path("sdp").asText());
		assertEquals("request", written.path("messageType").asText());
		Rule back = MAPPER.treeToValue(written, Rule.class);
		assertEquals(Rule.Leg.callee, back.getLeg());
		assertEquals(Rule.SdpRole.offer, back.getSdp());
	}

	@Test
	void theSchemaOffersTheirChoices() {
		JsonNode props = SettingsManager.generateSchemaNode(Crud.class, MAPPER).path("$defs").path("Rule").path("properties");
		assertTrue(props.has("leg") && props.has("sdp") && props.has("messageType"), props.toString());
	}
}
