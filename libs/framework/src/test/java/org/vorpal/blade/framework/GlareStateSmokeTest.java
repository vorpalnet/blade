package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow.GlareState;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.framework.v2.testing.DummySipFactory;
import org.vorpal.blade.framework.v2.testing.DummySipSession;
import org.vorpal.blade.framework.v2.testing.DummySipSessionsUtil;

/// Guards the glare contract that both `AsyncSipServlet.doRequest` and the REST
/// [org.vorpal.blade.framework.v2.transfer.api.TransferAPI] depend on:
/// `PROTECT` means a transaction is outstanding on that leg, and a second
/// request against it is answered `491 Request Pending`.
///
/// `TransferAPI` used to gate its 491 on an `EXPECT_ACK` attribute that nothing
/// ever wrote, so the check never fired and back-to-back transfers overlapped.
/// It now reads the state exercised here.
@DisplayName("glare state transitions")
class GlareStateSmokeTest {

	static class Sender extends org.vorpal.blade.framework.v3.Callflow {
		private static final long serialVersionUID = 1L;

		@Override
		public void process(SipServletRequest request) throws ServletException, IOException {
			sendRequest(request, (response) -> {
			});
		}
	}

	private DummyApplicationSession appSession;
	private DummySipSession session;

	@BeforeEach
	void setUp() throws Exception {
		Callflow.setSipFactory(new DummySipFactory());
		Callflow.setSipLogger(new CapturingLogger());
		Callflow.setSipUtil(new DummySipSessionsUtil());
		appSession = new DummyApplicationSession("glare");
		session = new DummySipSession(appSession);
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		Callflow.setSipUtil(null);
	}

	private void send(String method) throws Exception {
		DummyRequest request = new DummyRequest(appSession, method);
		request.setSession(session);
		new Sender().process(request);
	}

	/// A leg that has sent nothing is not in glare. This is what keeps the REST
	/// API from rejecting a perfectly good first transfer.
	@Test
	void anUntouchedSessionAllows() {
		assertEquals(GlareState.ALLOW, Callflow.getGlareState(session));
	}

	@Test
	void sendingAnInviteProtects() throws Exception {
		send(Callflow.INVITE);
		assertEquals(GlareState.PROTECT, Callflow.getGlareState(session));
	}

	/// The fire-and-forget case: a REFER holds the leg until its transaction
	/// finishes, so a second transfer arriving meanwhile glares.
	@Test
	void sendingAReferProtects() throws Exception {
		send(Callflow.REFER);
		assertEquals(GlareState.PROTECT, Callflow.getGlareState(session));
	}

	@Test
	void acknowledgingReleasesTheLeg() throws Exception {
		send(Callflow.INVITE);
		send(Callflow.ACK);
		assertEquals(GlareState.ALLOW, Callflow.getGlareState(session));
	}

	@Test
	void cancellingReleasesTheLeg() throws Exception {
		send(Callflow.INVITE);
		send(Callflow.CANCEL);
		assertEquals(GlareState.ALLOW, Callflow.getGlareState(session));
	}

	/// A mid-dialog method neither sets nor clears it — only the transaction
	/// boundaries move the state.
	@Test
	void anInfoLeavesTheStateAlone() throws Exception {
		send(Callflow.INVITE);
		send(Callflow.INFO);
		assertEquals(GlareState.PROTECT, Callflow.getGlareState(session));
	}
}
