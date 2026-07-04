package org.vorpal.blade.framework.v3;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v3.diagnostics.TraceLog;

/// Smoke-test driver for [Callflow#traceEvent] — the single trace recording
/// spine, called beside every sequence-diagram arrow in the copied v2 bodies.
/// Run via `main`, like the other v3 smoke tests. Container-free: the SIP
/// interfaces are `java.lang.reflect.Proxy` stand-ins backed by attribute
/// maps, with `toString()` returning the raw message text (the same contract
/// OCCAS's `SipServletMessageImpl` honors).
///
/// Proves: (1) a RECEIVE on an armed session records an `in` step pinned to
/// the handler FQN + method hint, raw message attached, no line; (2) a
/// received response labels by status; (3) a SEND with a sender callflow pins
/// the exact emitting method/line via captureStep; (4) a SEND without a
/// sender (servlet-level) pins the given class/hint; (5) a disarmed session
/// records nothing; (6) the session id resolves from the X-Vorpal-Session
/// header at an app's first dispatch (before v2 populates the attribute) and
/// is stored back; (7) [Callflow#handlerName] trims a lambda's synthetic
/// class name to its owner and falls back cleanly.
public final class TraceEventSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		testReceiveRequestPinsHandler();
		testReceiveResponseLabelsByStatus();
		testSendWithSenderPinsLine();
		testSendWithoutSenderPinsClass();
		testDisarmedRecordsNothing();
		testSessionIdResolvedFromHeaderAtDispatch();
		testLambdaCallbackResolution();
		testInheritedSendPinsBaseFrame();
		testDispatchedCallflowPinsProcessDeclarer();
		testHandlerName();
		summary();
	}

	// ------------------------------------------------------------- proxies

	private static SipApplicationSession appSession() {
		Map<String, Object> attrs = new HashMap<>();
		return (SipApplicationSession) Proxy.newProxyInstance(
				TraceEventSmokeTest.class.getClassLoader(),
				new Class<?>[] { SipApplicationSession.class },
				(proxy, method, a) -> {
					switch (method.getName()) {
					case "getAttribute":
						return attrs.get(a[0]);
					case "setAttribute":
						attrs.put((String) a[0], a[1]);
						return null;
					case "removeAttribute":
						attrs.remove(a[0]);
						return null;
					case "toString":
						return "appSession-proxy";
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == a[0];
					default:
						return defaultValue(method.getReturnType());
					}
				});
	}

	private static InvocationHandler messageHandler(Map<String, Object> attrs, String raw,
			SipApplicationSession app, Map<String, Object> special) {
		return (proxy, method, a) -> {
			switch (method.getName()) {
			case "getAttribute":
				return attrs.get(a[0]);
			case "setAttribute":
				attrs.put((String) a[0], a[1]);
				return null;
			case "getApplicationSession":
				return app;
			case "toString":
				return raw;
			case "hashCode":
				return System.identityHashCode(proxy);
			case "equals":
				return proxy == a[0];
			default:
				if (special.containsKey(method.getName())) {
					return special.get(method.getName());
				}
				return defaultValue(method.getReturnType());
			}
		};
	}

	private static SipServletRequest request(SipApplicationSession app, String method, String raw) {
		return request(app, method, raw, null);
	}

	private static SipServletRequest request(SipApplicationSession app, String method, String raw,
			String vorpalSessionHeader) {
		Map<String, Object> special = new HashMap<>();
		special.put("getMethod", method);
		if (vorpalSessionHeader != null) {
			special.put("getHeader", vorpalSessionHeader); // X-Vorpal-Session lookup
			// the resolver stores the dialog id on the SipSession
			Map<String, Object> sessAttrs = new HashMap<>();
			special.put("getSession", Proxy.newProxyInstance(TraceEventSmokeTest.class.getClassLoader(),
					new Class<?>[] { javax.servlet.sip.SipSession.class },
					(proxy, m, a) -> {
						switch (m.getName()) {
						case "getAttribute":
							return sessAttrs.get(a[0]);
						case "setAttribute":
							sessAttrs.put((String) a[0], a[1]);
							return null;
						case "toString":
							return "sipSession-proxy";
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == a[0];
						default:
							return defaultValue(m.getReturnType());
						}
					}));
		}
		return (SipServletRequest) Proxy.newProxyInstance(TraceEventSmokeTest.class.getClassLoader(),
				new Class<?>[] { SipServletRequest.class },
				messageHandler(new HashMap<>(), raw, app, special));
	}

	private static SipServletResponse response(SipApplicationSession app, int status,
			SipServletRequest request, String raw) {
		Map<String, Object> special = new HashMap<>();
		special.put("getStatus", status);
		special.put("getRequest", request);
		return (SipServletResponse) Proxy.newProxyInstance(TraceEventSmokeTest.class.getClassLoader(),
				new Class<?>[] { SipServletResponse.class },
				messageHandler(new HashMap<>(), raw, app, special));
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		return null;
	}

	/// A concrete v3 callflow so SEND events have a real emitting frame for
	/// captureStep to pin.
	static class Probe extends Callflow {
		private static final long serialVersionUID = 1L;

		@Override
		public void process(SipServletRequest request) throws ServletException, java.io.IOException {
			// unused — the probe never actually runs a call
		}

		void emit(SipServletRequest request) {
			Callflow.traceEvent(Direction.SEND, request, null, null, null, this);
		}
	}

	/// A thin subclass that INHERITS everything — the TransferInitialInvite
	/// shape: its sends run in Probe frames, and `process` is declared upstream.
	static final class ProbeChild extends Probe {
		private static final long serialVersionUID = 1L;
	}

	// --------------------------------------------------------------- tests

	private static final String RAW_INVITE = "INVITE sip:bob@example.com SIP/2.0\r\nFrom: <sip:alice@a>\r\n\r\nv=0\r\n";
	private static final String RAW_180 = "SIP/2.0 180 Ringing\r\nTo: <sip:bob@b>\r\n";

	private static void testReceiveRequestPinsHandler() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);

		Callflow.traceEvent(Direction.RECEIVE, request(app, "INVITE", RAW_INVITE), null,
				"org.vorpal.blade.framework.v2.transfer.BlindTransfer", "process", null);

		List<CallStep> ring = TraceLog.snapshot();
		check("receive records one step", ring.size() == 1);
		CallStep s = ring.get(0);
		check("direction in, kind/label from the request",
				CallStep.IN.equals(s.getDirection()) && "request".equals(s.getKind()) && "INVITE".equals(s.getLabel()));
		check("handler FQN + process hint, no line pin",
				"org.vorpal.blade.framework.v2.transfer.BlindTransfer".equals(s.getClassName())
						&& "process".equals(s.getMethodName()) && s.getLine() == -1);
		check("raw message captured", RAW_INVITE.equals(s.getMessage()));
		check("session trace list also grew", Callflow.getTrace(app).size() == 1);
	}

	private static void testReceiveResponseLabelsByStatus() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);
		SipServletRequest invite = request(app, "INVITE", RAW_INVITE);

		Callflow.traceEvent(Direction.RECEIVE, null, response(app, 180, invite, RAW_180),
				"org.example.UacServlet", "received", null);

		List<CallStep> ring = TraceLog.snapshot();
		check("received response recorded", ring.size() == 1);
		check("labeled by status with raw message",
				"response".equals(ring.get(0).getKind()) && "180".equals(ring.get(0).getLabel())
						&& RAW_180.equals(ring.get(0).getMessage()));
	}

	private static void testSendWithSenderPinsLine() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);

		new Probe().emit(request(app, "INVITE", RAW_INVITE));

		List<CallStep> ring = TraceLog.snapshot();
		check("send records one step", ring.size() == 1);
		CallStep s = ring.get(0);
		check("direction out via captureStep pin",
				CallStep.OUT.equals(s.getDirection()) && s.getClassName().endsWith("Probe")
						&& "emit".equals(s.getMethodName()) && s.getLine() > 0);
		check("send message captured", RAW_INVITE.equals(s.getMessage()));
	}

	private static void testSendWithoutSenderPinsClass() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);
		SipServletRequest invite = request(app, "INVITE", RAW_INVITE);

		// servlet-level send (glare 491 / 501 / server-generated): no callflow
		Callflow.traceEvent(Direction.SEND, null, response(app, 501, invite, "SIP/2.0 501 Not Implemented\r\n"),
				"org.example.UasServlet", "doRequest", null);

		List<CallStep> ring = TraceLog.snapshot();
		check("servlet-level send recorded", ring.size() == 1);
		CallStep s = ring.get(0);
		check("pinned to the servlet class, no line",
				CallStep.OUT.equals(s.getDirection()) && "org.example.UasServlet".equals(s.getClassName())
						&& "doRequest".equals(s.getMethodName()) && s.getLine() == -1);
	}

	private static void testDisarmedRecordsNothing() {
		TraceLog.clear();
		SipApplicationSession app = appSession(); // not armed, diagnostics off

		Callflow.traceEvent(Direction.RECEIVE, request(app, "INVITE", RAW_INVITE), null,
				"org.example.UasServlet", "process", null);
		check("disarmed session records nothing", TraceLog.snapshot().isEmpty());
	}

	/// The live bug from 2026-07-04: dispatch runs before v2 populates the
	/// session-id attribute, so early steps landed in a "(no session id)"
	/// bucket. traceEvent must resolve the id the way v2 will moments later —
	/// header first — and store it on the appSession. (The true-chain-head
	/// MINTING path needs the container's SipSessionsUtil, so it can't run
	/// here; header resolution is the case that fragmented the viewer.)
	private static void testSessionIdResolvedFromHeaderAtDispatch() {
		TraceLog.clear();
		SipApplicationSession app = appSession(); // VORPAL_SESSION attr NOT set
		Callflow.enableTrace(app);

		Callflow.traceEvent(Direction.RECEIVE, request(app, "INVITE", RAW_INVITE, "CAFE1234:0001"), null,
				"org.example.UasServlet", "process", null);

		List<CallStep> ring = TraceLog.snapshot();
		check("arrival with header-only id still records", ring.size() == 1);
		check("session id resolved from the X-Vorpal-Session header",
				"CAFE1234".equals(ring.get(0).getSessionId()));
		check("resolved id stored on the appSession for the rest of the call",
				"CAFE1234".equals(Callflow.getVorpalSessionId(app)));
	}

	/// A received response handled by a callback lambda (the step-12 case from
	/// the live run): traceEvent introspects the serializable lambda
	/// (`writeReplace` → SerializedLambda) and pins the OWNER class + the
	/// ENCLOSING method — so the viewer highlights e.g.
	/// `InitialInvite.processContinue` instead of showing unmarked source.
	private static void testLambdaCallbackResolution() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);
		SipServletRequest invite = request(app, "INVITE", RAW_INVITE);
		Callback<SipServletResponse> callback = (resp) -> { /* the handler lambda */ };

		Callflow.traceEvent(Direction.RECEIVE, null, response(app, 180, invite, RAW_180),
				callback, "received", null);

		List<CallStep> ring = TraceLog.snapshot();
		check("lambda-handled receive recorded", ring.size() == 1);
		CallStep s = ring.get(0);
		check("pinned to the lambda's OWNER class (got: " + s.getClassName() + ")",
				TraceEventSmokeTest.class.getName().equals(s.getClassName()));
		check("method resolved to the ENCLOSING method (got: " + s.getMethodName() + ")",
				"testLambdaCallbackResolution".equals(s.getMethodName()));
	}

	/// The step-15 case from the live run: TransferInitialInvite sends from code
	/// INHERITED from InitialInvite — the emitting frame carries the BASE
	/// class's name, so an exact-concrete-class walk found nothing (?:-1). The
	/// walk now matches ancestor frames and records the FRAME's class, keeping
	/// the line aligned with the file the viewer shows.
	private static void testInheritedSendPinsBaseFrame() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);

		new ProbeChild().emit(request(app, "INVITE", RAW_INVITE)); // emit() lives in Probe

		List<CallStep> ring = TraceLog.snapshot();
		check("inherited send recorded", ring.size() == 1);
		CallStep s = ring.get(0);
		check("pinned to the FRAME's class (base), real line (got: " + s.getClassName() + ":" + s.getLine() + ")",
				s.getClassName().endsWith("Probe") && "emit".equals(s.getMethodName()) && s.getLine() > 0);
	}

	/// The receive-side mirror: a dispatched thin subclass pins the class that
	/// DECLARES process — highlighting the empty shell would mark nothing.
	private static void testDispatchedCallflowPinsProcessDeclarer() {
		TraceLog.clear();
		SipApplicationSession app = appSession();
		Callflow.enableTrace(app);

		Callflow.traceEvent(Direction.RECEIVE, request(app, "INVITE", RAW_INVITE), null,
				new ProbeChild(), "process", null);

		List<CallStep> ring = TraceLog.snapshot();
		check("dispatched receive recorded", ring.size() == 1);
		CallStep s = ring.get(0);
		check("pinned to the process-declaring class (got: " + s.getClassName() + ")",
				s.getClassName().endsWith("Probe") && !s.getClassName().endsWith("ProbeChild")
						&& "process".equals(s.getMethodName()));
	}

	private static void testHandlerName() {
		Callback<SipServletRequest> lambda = (req) -> { /* no-op */ };
		String owner = Callflow.handlerName(lambda, "fallback");
		check("lambda handler trims to its owning class (got: " + owner + ")",
				TraceEventSmokeTest.class.getName().equals(owner));
		check("null handler falls back", "fallback".equals(Callflow.handlerName(null, "fallback")));
		check("plain object keeps its FQN",
				"java.lang.String".equals(Callflow.handlerName("x", "fallback")));
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}

	private static void summary() {
		System.out.println("TraceEventSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
