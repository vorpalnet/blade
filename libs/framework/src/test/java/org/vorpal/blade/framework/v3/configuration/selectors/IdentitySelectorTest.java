package org.vorpal.blade.framework.v3.configuration.selectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.databind.ObjectMapper;

class IdentitySelectorTest {

	private static final String CERT = "https://cert.example.com/sp.pem";

	private static String b64(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static String identity(String attest, long iat) {
		String header = "{\"alg\":\"ES256\",\"ppt\":\"shaken\",\"typ\":\"passport\",\"x5u\":\"" + CERT + "\"}";
		String body = "{\"attest\":\"" + attest + "\",\"dest\":{\"tn\":[\"18005550001\"]},\"iat\":" + iat
				+ ",\"orig\":{\"tn\":\"18165551234\"},\"origid\":\"de305d54-75b4-431b-adb2-eb6b9e546014\"}";
		return b64(header) + "." + b64(body) + ".c2lnbmF0dXJl;info=<" + CERT + ">;alg=ES256;ppt=\"shaken\"";
	}

	@Test
	void parsesShakenClaims() {
		long now = System.currentTimeMillis() / 1000;
		Map<String, String> c = IdentitySelector.parse(identity("A", now - 5));

		assertEquals("A", c.get("attest"));
		assertEquals("18165551234", c.get("origTn"));
		assertEquals("18005550001", c.get("destTn"));
		assertEquals("de305d54-75b4-431b-adb2-eb6b9e546014", c.get("origid"));
		assertEquals("shaken", c.get("ppt"));
		assertEquals(CERT, c.get("x5u"));
		assertEquals("ES256", c.get("alg"));
		long age = Long.parseLong(c.get("age"));
		assertTrue(age >= 5 && age < 60, "age " + age);
	}

	@Test
	void rejectsValuesThatAreNotAPassport() {
		assertNull(IdentitySelector.parse(null));
		assertNull(IdentitySelector.parse("not-a-jws"));
		assertNull(IdentitySelector.parse("a.b;info=<x>"));
		assertNull(IdentitySelector.parse("%%%.%%%.%%%"));
	}

	@Test
	void storesBareAndNamespacedValues() {
		MemoryContext ctx = new MemoryContext();
		new IdentitySelector("stir").extract(ctx,
				Collections.singletonMap("Identity", identity("C", System.currentTimeMillis() / 1000)));

		assertEquals("C", ctx.get("stir"));
		assertEquals("C", ctx.get("stir.attest"));
		assertEquals("18165551234", ctx.get("stir.origTn"));
		assertTrue(new Expression("${stir} == C && ${stir.origTn} matches '1\\d{10}'").evaluate(ctx));
	}

	@Test
	void unsignedCallStoresNothing() {
		MemoryContext ctx = new MemoryContext();
		new IdentitySelector("stir").extract(ctx, Collections.emptyMap());

		assertNull(ctx.get("stir"));
		assertTrue(new Expression("${stir} == ''").evaluate(ctx));
	}

	@Test
	void roundTripsAsIdentityType() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Selector s = mapper.readValue("{\"type\":\"identity\",\"id\":\"stir\"}", Selector.class);
		assertTrue(s instanceof IdentitySelector);
		assertEquals("Identity", s.getAttribute());
		assertTrue(mapper.writeValueAsString(s).contains("\"type\":\"identity\""));
	}
}
