package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.servlet.sip.SipServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// Pins [Callflow#dropResponseToCancel]: an application must never answer a CANCEL, and the
/// framework drops the attempt rather than letting it reach the wire.
///
/// The container gives an application no protection here. Building a response to a CANCEL succeeds
/// — only ACK is refused — and the container does not decline to send it the way it declines every
/// other response to a finished transaction. So it really is sent, as a second final response to a
/// transaction the container already answered. That is invisible except in a trace, which is why
/// the rule lives in the framework instead of in each author's memory.
class CancelResponseSmokeTest {

	@BeforeAll
	static void installContainerStandIns() {
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipLogger(new CapturingLogger());
	}

	@AfterAll
	static void removeContainerStandIns() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
	}

	private static SipServletResponse responseTo(String method, int status) throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("test");
		DetachedRequest request = new DetachedRequest(appSession, method);
		request.setSession(new DetachedSipSession(appSession));
		return request.createResponse(status);
	}

	@Nested
	@DisplayName("a response to a CANCEL")
	class Cancel {

		@Test
		void isDropped() throws Exception {
			assertTrue(Callflow.dropResponseToCancel(responseTo("CANCEL", 200)));
		}

		@Test
		void isDroppedWhateverTheStatus() throws Exception {
			// The whole transaction belongs to the container, so there is no status an application
			// could send that would be correct — not a provisional, not an error, not a 200.
			assertTrue(Callflow.dropResponseToCancel(responseTo("CANCEL", 100)));
			assertTrue(Callflow.dropResponseToCancel(responseTo("CANCEL", 481)));
			assertTrue(Callflow.dropResponseToCancel(responseTo("CANCEL", 500)));
		}

		@Test
		void saysSoRatherThanVanishing() throws Exception {
			// Silently eating the send would only move the confusion from "why are there two 200s"
			// to "why did my response do nothing". The message is what teaches the rule.
			CapturingLogger.begin();
			Callflow.dropResponseToCancel(responseTo("CANCEL", 200));
			List<String> logged = CapturingLogger.end();

			assertTrue(logged.stream().anyMatch(line -> line.contains("CANCEL")),
					"expected a warning naming CANCEL, got: " + logged);
		}
	}

	@Nested
	@DisplayName("everything else")
	class Untouched {

		@Test
		void byeIsAnsweredNormally() throws Exception {
			// The case that matters most: Terminate answers a BYE and must keep doing so.
			assertFalse(Callflow.dropResponseToCancel(responseTo("BYE", 200)));
		}

		@Test
		void inviteAndInfoAreAnsweredNormally() throws Exception {
			assertFalse(Callflow.dropResponseToCancel(responseTo("INVITE", 200)));
			assertFalse(Callflow.dropResponseToCancel(responseTo("INVITE", 180)));
			assertFalse(Callflow.dropResponseToCancel(responseTo("INFO", 200)));
		}

		@Test
		void nullIsNotSomethingToWarnAbout() {
			// sendResponse already tolerates null; this guard must not turn that into a log line.
			assertFalse(Callflow.dropResponseToCancel(null));
		}
	}
}
