package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/// What a webhook receives, and the line a person reads.
class NotifierTest {

	@Test
	void aNoticeIsOneFactInOneLine() {
		ObjectNode n = Notifier.notice("trunk", true, "trunk_a", "engine0", "403 Forbidden", "domain_a",
				"https://admin.example.net/blade/dashboard/", 1792046006952L);
		assertEquals("raised", n.path("state").asText());
		assertEquals("2026-10-15T06:33:26.952Z", n.path("at").asText());
		assertEquals("RAISED trunk trunk_a on engine0 (domain_a): 403 Forbidden", n.path("text").asText());
		assertEquals("https://admin.example.net/blade/dashboard/", n.path("board").asText());
		assertEquals("CLEARED problem cert:x on engine0 (domain_a)",
				Notifier.notice("problem", false, "cert:x", "engine0", null, "domain_a", null, 0).path("text").asText());
	}
}
