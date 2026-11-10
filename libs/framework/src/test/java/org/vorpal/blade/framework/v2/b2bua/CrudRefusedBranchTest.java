package org.vorpal.blade.framework.v2.b2bua;

import static org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.sip.DetachedSipURI;
import org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.RecordingFactory;
import org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.RecordingRequest;
import org.vorpal.blade.framework.v2.b2bua.SerialHuntCancelTest.ManualTimers;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.crud.RejectOperation;

/// A CRUD rule refuses one destination of a hunt or a race sent through the
/// baseline (four-argument) forms: that destination is dropped and the rest
/// are tried; the caller is refused only when every destination is.
@DisplayName("a CRUD rule refusing one destination")
class CrudRefusedBranchTest {

	static class Out extends Callflow {
		private static final long serialVersionUID = 1L;
		final List<SipServletRequest> destinations = new LinkedList<>();
		boolean race;

		@Override
		public void process(SipServletRequest caller) throws javax.servlet.ServletException, java.io.IOException {
			if (race) {
				sendRequestsInParallel(3000, destinations, (response) -> {
				}, null);
			} else {
				sendRequestsInSerial(3000, destinations, (response) -> {
				}, null);
			}
		}
	}

	private RecordingRequest caller;
	private Out out;
	/// SettingsManager's logger before this test: a capturing one left behind
	/// turns analytics on for the tests after it.
	private org.vorpal.blade.framework.v2.logging.Logger before;

	@BeforeEach
	void setUp() throws Exception {
		RecordingFactory factory = new RecordingFactory();
		Callflow.setSipFactory(factory);
		Callflow.setSipLogger(new CapturingLogger());
		before = SettingsManager.getSipLogger();
		SettingsManager.setSipLogger(new CapturingLogger());
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		Callflow.setTimerService(new ManualTimers());
		wire.clear();

		DetachedApplicationSession appSession = new DetachedApplicationSession("refused");
		caller = new RecordingRequest(appSession, "INVITE") {
			private static final long serialVersionUID = 1L;

			@Override
			public SipServletResponse createResponse(int status) {
				return new DetachedResponse(this, status) {
					private static final long serialVersionUID = 1L;

					@Override
					public void send() {
						wire.add(String.valueOf(getStatus()));
					}
				};
			}
		};
		DetachedSipSession callerSession = new DetachedSipSession(appSession);
		caller.setSession(callerSession);
		callerSession.setActiveInvite(caller);
		caller.setRequestURI(new DetachedSipURI("sip:agents@example.com"));

		out = new Out();
		for (String host : new String[] { "a1", "a2", "a3" }) {
			out.destinations.add(factory.createRequest(appSession, "INVITE",
					factory.createAddress("<sip:caller@example.com>"),
					factory.createAddress("<sip:agent@" + host + ".example.com>")));
		}
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		Callflow.setSipUtil(null);
		Callflow.setTimerService(null);
		SettingsManager.setSipLogger(before);
	}

	private void refuse(int... which) throws Exception {
		for (int i : which) new RejectOperation(403, null).process(out.destinations.get(i));
	}

	@Test
	void aHuntSkipsTheRefusedDestination() throws Exception {
		refuse(0);
		out.process(caller);
		assertEquals(List.of("INVITE"), wire, "a2 is called; the caller is not refused");
		assertEquals(1, out.destinations.size(), "a3 is still to try");
	}

	@Test
	void aRaceRingsTheOthers() throws Exception {
		out.race = true;
		refuse(1);
		out.process(caller);
		assertEquals(List.of("INVITE", "INVITE"), wire, "a1 and a3 ring; the caller is not refused");
		assertEquals(3, out.destinations.size(), "the application's list keeps every branch it built");
	}

	@Test
	void everyDestinationRefusedRefusesTheCaller() throws Exception {
		out.race = true;
		refuse(0, 1, 2);
		out.process(caller);
		assertEquals(List.of("403"), wire);
	}

	@Test
	void aRefusedDestinationIsNotDialledWhenTheCallerHasGone() throws Exception {
		refuse(0, 1, 2);
		caller.committed = true; // the caller cancelled while the rules ran
		org.junit.jupiter.api.Assertions.assertThrows(javax.servlet.ServletException.class, () -> out.process(caller));
		assertEquals(List.of(), wire, "nothing is sent, not even to the caller");
	}
}
