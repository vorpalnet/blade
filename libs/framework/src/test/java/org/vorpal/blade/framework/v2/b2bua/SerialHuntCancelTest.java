package org.vorpal.blade.framework.v2.b2bua;

import static org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.TimerService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.sip.DetachedSipURI;
import org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.RecordingFactory;
import org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.RecordingRequest;
import org.vorpal.blade.framework.v2.b2bua.CancelDuringCallStartedTest.RecordingResponse;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.Callflow;

/// A caller who cancels during a serial hunt: the leg ringing now is ended, and
/// no further destination is tried. The hunt stops when `dialogResponse` empties
/// its list, which [Callflow#sendRequestsInSerial] documents.
@DisplayName("a caller who cancels during a serial hunt")
class SerialHuntCancelTest {

	/// Holds the hunt's timer so a test can fire it.
	static class ManualTimers implements TimerService {
		final List<Callback<ServletTimer>> pending = new ArrayList<>();

		@SuppressWarnings("unchecked")
		private ServletTimer timer(SipApplicationSession appSession, Serializable info) {
			pending.add((Callback<ServletTimer>) info);
			String id = "timer-" + pending.size();
			return new ServletTimer() {
				public void cancel() {
				}

				public SipApplicationSession getApplicationSession() {
					return appSession;
				}

				public String getId() {
					return id;
				}

				public Serializable getInfo() {
					return info;
				}

				public long getTimeRemaining() {
					return 0;
				}

				public long scheduledExecutionTime() {
					return 0;
				}
			};
		}

		@Override
		public ServletTimer createTimer(SipApplicationSession appSession, long delay, boolean isPersistent,
				Serializable info) {
			return timer(appSession, info);
		}

		@Override
		public ServletTimer createTimer(SipApplicationSession appSession, long delay, long period,
				boolean fixedDelay, boolean isPersistent, Serializable info) {
			return timer(appSession, info);
		}

		void fire() throws Exception {
			pending.remove(pending.size() - 1).acceptThrows(null);
		}
	}

	/// What genrec2's Qfiniti leg does: hunt the recorders one at a time.
	static class Hunt extends Callflow {
		private static final long serialVersionUID = 1L;
		final List<SipServletRequest> recorders = new LinkedList<>();
		final List<Integer> delivered = new ArrayList<>();
		boolean clearOnCancel = true;

		@Override
		public void process(SipServletRequest caller) throws javax.servlet.ServletException, java.io.IOException {
			sendRequestsInSerial(3000, recorders, (response) -> {
				delivered.add(response.getStatus());
			}, (response) -> {
				if (endIfAbandoned(caller, response) && clearOnCancel) {
					recorders.clear();
				}
			});
		}
	}

	private ManualTimers timers;
	private RecordingRequest caller;
	private Hunt hunt;
	/// The request the hunt sends first: the head of the list it is handed.
	private SipServletRequest first;

	@BeforeEach
	void setUp() throws Exception {
		RecordingFactory factory = new RecordingFactory();
		timers = new ManualTimers();
		Callflow.setSipFactory(factory);
		Callflow.setSipLogger(new CapturingLogger());
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		Callflow.setTimerService(timers);
		wire.clear();

		DetachedApplicationSession appSession = new DetachedApplicationSession("hunt");
		caller = new RecordingRequest(appSession, "INVITE");
		caller.setSession(new DetachedSipSession(appSession));
		caller.setRequestURI(new DetachedSipURI("sip:recorder@example.com"));

		hunt = new Hunt();
		for (String host : new String[] { "rec1", "rec2", "rec3" }) {
			hunt.recorders.add(factory.createRequest(appSession, "INVITE",
					factory.createAddress("<sip:caller@example.com>"),
					factory.createAddress("<sip:rec@" + host + ".example.com>")));
		}
		first = hunt.recorders.get(0);
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		Callflow.setSipUtil(null);
		Callflow.setTimerService(null);
	}

	private static void deliver(SipServletResponse response) throws Exception {
		Callback<SipServletResponse> callback = Callflow.pullCallback(response);
		assertNotNull(callback, "no callback was registered for this response");
		callback.acceptThrows(response);
	}

	@Test
	void triesNoFurtherRecorder() throws Exception {
		hunt.process(caller);

		caller.committed = true;
		deliver(new RecordingResponse(first, 180));
		deliver(new RecordingResponse(first, 487));

		assertEquals(List.of("INVITE", "CANCEL"), wire, "no INVITE to rec2 or rec3");
		assertEquals(List.of(487), hunt.delivered, "the hunt still ends with a final response");
	}

	@Test
	void withoutEmptyingTheListTheHuntMovesOn() throws Exception {
		hunt.clearOnCancel = false;
		hunt.process(caller);

		caller.committed = true;
		deliver(new RecordingResponse(first, 180));
		deliver(new RecordingResponse(first, 487));

		assertEquals(List.of("INVITE", "CANCEL", "INVITE"), wire, "rec2 is called for a caller who is gone");
	}

	@Test
	void aTimerAfterTheCancelSendsNoSecondCancel() throws Exception {
		hunt.process(caller);

		caller.committed = true;
		deliver(new RecordingResponse(first, 180));
		timers.fire(); // rec1 never answered our CANCEL within the hunt's timer

		assertEquals(List.of("INVITE", "CANCEL"), wire);
		assertEquals(List.of(408), hunt.delivered);
	}
}
