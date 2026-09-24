package org.vorpal.blade.services.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PartyRequestsTest {

	@Test
	void anEmptyListAllowsNoOne() {
		assertFalse(PartyRequests.allowed(List.of(), "sip:supervisor@pbx.example.com"));
		assertFalse(PartyRequests.allowed(null, "sip:supervisor@pbx.example.com"));
	}

	@Test
	void onlyAWholeMatchIsDialled() {
		List<String> targets = List.of("sip:(supervisor|billing)@pbx\\.example\\.com");
		assertTrue(PartyRequests.allowed(targets, "sip:supervisor@pbx.example.com"));
		assertFalse(PartyRequests.allowed(targets, "sip:supervisor@pbx.example.com.evil.org"));
		assertFalse(PartyRequests.allowed(targets, "sip:+19005550100@carrier.example"));
	}
}
