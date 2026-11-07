package org.vorpal.blade.framework.v3.transfer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// What selects an attended transfer on its own: a `Refer-To` carrying `Replaces`.
class AttendedTransferTest {

	@BeforeEach
	void wire() {
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipLogger(new CapturingLogger());
	}

	private static DetachedRequest refer(String referTo) throws Exception {
		DetachedRequest refer = new DetachedRequest(new DetachedApplicationSession("app"), "REFER",
				"<sip:bob@example.com>;tag=b1", "<sip:alice@example.com>;tag=a1");
		refer.setHeader("Refer-To", referTo);
		return refer;
	}

	@Test
	void aReplacesHeaderMeansAttended() throws Exception {
		assertTrue(AttendedTransfer.hasReplaces(
				refer("<sip:carol@example.com?Replaces=consult-1%40example.com%3Bto-tag%3Da%3Bfrom-tag%3Db>")));
	}

	@Test
	void theHeaderNameIsCaseInsensitive() throws Exception {
		assertTrue(AttendedTransfer.hasReplaces(refer("<sip:carol@example.com?replaces=x>")));
	}

	@Test
	void aPlainReferToIsNot() throws Exception {
		assertFalse(AttendedTransfer.hasReplaces(refer("<sip:carol@example.com>")));
	}

	@Test
	void anotherUriHeaderIsNot() throws Exception {
		assertFalse(AttendedTransfer.hasReplaces(refer("<sip:carol@example.com?Subject=hello>")));
	}

	@Test
	void noReferToIsNot() throws Exception {
		DetachedRequest refer = new DetachedRequest(new DetachedApplicationSession("app"), "REFER",
				"<sip:bob@example.com>;tag=b1", "<sip:alice@example.com>;tag=a1");
		assertFalse(AttendedTransfer.hasReplaces(refer));
	}
}
