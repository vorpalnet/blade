package org.vorpal.blade.framework.v2.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Guards the attribute key that carries the initial INVITE between callflows.
///
/// `BlindTransfer` used to read `"INITIAL_INVITE"` from the **application**
/// session while both writers store `"initial_invite"` on the **SipSession**, so
/// the lookup always returned null and `preserveInviteHeaders` never ran — the
/// configured `preserveInviteHeaders` list silently did nothing on a blind
/// transfer. Nothing failed; the feature was simply absent.
///
/// A unit test cannot easily drive a full REFER, so this asserts the contract
/// directly: every class that names this attribute agrees on the literal, and
/// the reader reads it from the session the writers write to.
@DisplayName("initial-INVITE session attribute")
class InitialInviteAttributeTest {

	private static final String SRC = "src/main/java/org/vorpal/blade/framework/";

	private static String read(String relative) throws IOException {
		return new String(Files.readAllBytes(Path.of(SRC + relative)));
	}

	/// The declared literal, per class that declares one.
	private static Map<String, String> declaredLiterals() throws IOException {
		Map<String, String> found = new LinkedHashMap<>();
		found.put("BlindTransfer", literal(read("v2/transfer/BlindTransfer.java"), "INITIAL_INVITE_ATTR"));
		found.put("Dialog", literal(read("v2/transfer/api/Dialog.java"), "INITIAL_INVITE_ATTR"));
		found.put("TransferInitialInvite",
				literal(read("v2/transfer/TransferInitialInvite.java"), "INITIAL_INVITE_SESSION_ATTR"));
		found.put("InitialInvite", literal(read("v2/b2bua/InitialInvite.java"), "ATTR_INITIAL_INVITE"));
		return found;
	}

	private static String literal(String source, String constant) {
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("String\\s+" + constant + "\\s*=\\s*\"([^\"]*)\"").matcher(source);
		assertTrue(m.find(), "could not find the declaration of " + constant);
		return m.group(1);
	}

	@Test
	void everyClassAgreesOnTheLiteral() throws Exception {
		Map<String, String> literals = declaredLiterals();
		for (Map.Entry<String, String> e : literals.entrySet()) {
			assertEquals("initial_invite", e.getValue(),
					e.getKey() + " disagrees on the initial-INVITE attribute name");
		}
	}

	/// The writers use the SipSession, so the reader must too. Reading it off the
	/// application session compiles and returns null forever.
	@Test
	void blindTransferReadsItFromTheSipSession() throws Exception {
		String source = read("v2/transfer/BlindTransfer.java");
		int at = source.indexOf("INITIAL_INVITE_ATTR);");
		assertTrue(at > 0, "BlindTransfer no longer reads INITIAL_INVITE_ATTR");
		String statement = source.substring(Math.max(0, at - 220), at);
		assertTrue(statement.contains("getSession()"), "must read from the SipSession: " + statement);
		assertTrue(!statement.contains("getApplicationSession()"),
				"must not read from the application session: " + statement);
	}

	/// Both writers put it on the SipSession.
	@Test
	void bothWritersUseTheSipSession() throws Exception {
		assertTrue(read("v2/transfer/TransferInitialInvite.java")
				.contains("request.getSession().setAttribute(INITIAL_INVITE_SESSION_ATTR"),
				"TransferInitialInvite should store the initial INVITE on the SipSession");
		assertTrue(read("v2/b2bua/InitialInvite.java")
				.contains("bobRequest.getSession().setAttribute(ATTR_INITIAL_INVITE"),
				"InitialInvite should store the initial INVITE on the SipSession");
	}

	/// Sanity: the sibling REFER attribute genuinely is an application-session
	/// attribute with an upper-case name, which is what made the mismatch above
	/// look plausible.
	@Test
	void theReferAttributeIsDeliberatelyDifferent() throws Exception {
		assertEquals("INITIAL_REFER", literal(read("v2/transfer/BlindTransfer.java"), "INITIAL_REFER_ATTR"));
		assertEquals("INITIAL_REFER", literal(read("v2/transfer/api/TransferAPI.java"), "INITIAL_REFER_ATTR"));
	}
}
