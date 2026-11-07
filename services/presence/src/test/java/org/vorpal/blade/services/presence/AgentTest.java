package org.vorpal.blade.services.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AgentTest {

	private static Agent.Publication pub(boolean open, long expiresAt) {
		Agent.Publication p = new Agent.Publication();
		p.open = open;
		p.expiresAt = expiresAt;
		return p;
	}

	@Test
	void anyOpenDeviceMakesTheAgentReady() {
		Agent a = new Agent("bob@example.com");
		assertEquals("offline", a.status(0));
		a.publications.put("desk", pub(false, 1000));
		assertEquals("not-ready", a.status(0));
		a.publications.put("browser", pub(true, 1000));
		assertTrue(a.ready(0));
		assertEquals("ready", a.status(0));
	}

	@Test
	void anExpiredPublicationCountsForNothing() {
		Agent a = new Agent("bob@example.com");
		a.publications.put("browser", pub(true, 1000));
		assertFalse(a.ready(1000));
		assertEquals("offline", a.status(1000)); // before a request prunes it
		a.prune(1000);
		assertTrue(a.publications.isEmpty());
		assertEquals("offline", a.status(1000));
	}

	@Test
	void ringingBusyAndHeldAreNotReady() {
		Agent a = new Agent("bob@example.com");
		a.publications.put("browser", pub(true, 1000));
		a.reservedFor = "call-1";
		assertEquals("ringing", a.status(0));
		assertFalse(a.ready(0));
		a.reservedFor = null;
		a.busyWith = "call-1";
		assertEquals("busy", a.status(0));
		a.busyWith = null;
		a.held = true;
		assertEquals("not-ready", a.status(0));
		assertFalse(a.ready(0));
	}

	@Test
	void documentSaysOpenOnlyWhenReady() {
		Agent a = new Agent("bob@example.com");
		a.publications.put("browser", pub(true, 1000));
		String ready = new String(Pidf.document(a, 0), StandardCharsets.UTF_8);
		assertTrue(ready.contains("<basic>open</basic>"));
		a.busyWith = "call-1";
		String busy = new String(Pidf.document(a, 0), StandardCharsets.UTF_8);
		assertTrue(busy.contains("<basic>closed</basic>"));
		assertTrue(busy.contains("on-the-phone"));
		assertTrue(busy.contains("entity=\"sip:bob@example.com\""));
	}

	@Test
	void skillTuplesBecomeSkillsAndOtherTuplesDoNot() {
		String body = "<presence xmlns='urn:ietf:params:xml:ns:pidf' entity='sip:bob@example.com'>"
				+ "<tuple id='Skill-Spanish'><status><basic>open</basic></status></tuple>"
				+ "<p:tuple id=\"skill-billing\"><p:status><p:basic>closed</p:basic></p:status></p:tuple>"
				+ "<tuple id='t1234'><status><basic>closed</basic></status></tuple></presence>";
		Pidf.Published p = Pidf.read("application/pidf+xml", body.getBytes(StandardCharsets.UTF_8));
		assertTrue(p.open);
		assertEquals(Boolean.TRUE, p.skills.get("spanish"));
		assertEquals(Boolean.FALSE, p.skills.get("billing"));
		assertEquals(2, p.skills.size());
		assertNull(Pidf.read("text/plain", body.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void readyForOnlyTheSkillsPublishedOpen() {
		Agent a = new Agent("bob@example.com");
		Agent.Publication p = pub(true, 1000);
		p.skills.put("spanish", true);
		p.skills.put("billing", false);
		a.publications.put("browser", p);
		assertEquals(Arrays.asList("billing", "spanish"), new java.util.ArrayList<>(a.skills(0)));
		assertTrue(a.readyFor("spanish", 0));
		assertFalse(a.readyFor("billing", 0));
		assertFalse(a.readyFor("french", 0));
		a.held = true;
		assertFalse(a.readyFor("spanish", 0));
		a.held = false;
		assertTrue(a.skills(1000).isEmpty()); // the publication expired, and its skills with it
		String doc = new String(Pidf.document(a, 0), StandardCharsets.UTF_8);
		assertTrue(doc.contains("<tuple id=\"skill-spanish\"><status><basic>open</basic>"));
		assertTrue(doc.contains("<tuple id=\"skill-billing\"><status><basic>closed</basic>"));
	}
}
