package org.vorpal.blade.framework.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class SipAddressPatternTest {

	private static final Pattern P = Pattern.compile(Configuration.SIP_ADDRESS_PATTERN, Pattern.DOTALL);

	private static Matcher match(String address) {
		Matcher m = P.matcher(address);
		assertTrue(m.matches(), address);
		return m;
	}

	@Test
	void nameAddrWithEverything() {
		Matcher m = match("\"Alice Smith\" <sips:+18165551234@10.0.0.1:5060;user=phone>;tag=abc");
		assertEquals("Alice Smith", m.group("name"));
		assertEquals("sips", m.group("proto"));
		assertEquals("+18165551234", m.group("user"));
		assertEquals("10.0.0.1", m.group("host"));
		assertEquals("5060", m.group("port"));
		assertEquals("user=phone", m.group("uriparams"));
		assertEquals("tag=abc", m.group("addrparams"));
	}

	@Test
	void bareAndUnquotedForms() {
		Matcher bare = match("sip:alice@example.com;tag=9");
		assertEquals("", bare.group("name"));
		assertEquals("alice", bare.group("user"));
		assertEquals("tag=9", bare.group("uriparams"));

		Matcher unquoted = match("Alice <sip:alice@example.com:5061;transport=tls>;tag=1");
		assertEquals("Alice", unquoted.group("name"));
		assertEquals("5061", unquoted.group("port"));

		Matcher hostOnly = match("<sip:example.com>");
		assertNull(hostOnly.group("user"));
		assertEquals("example.com", hostOnly.group("host"));
	}

	@Test
	void hostileDisplayNamesStayFast() {
		StringBuilder spaces = new StringBuilder("\"");
		StringBuilder angles = new StringBuilder("\"");
		for (int i = 0; i < 16_000; i++) {
			spaces.append(' ');
			angles.append('<');
		}
		String spaced = spaces.append("\" <sip:a@b>").toString();
		String angled = angles.append("\" <sip:a@b>").toString();
		assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
			P.matcher(spaced).matches();
			P.matcher(angled).matches();
		});
	}
}
