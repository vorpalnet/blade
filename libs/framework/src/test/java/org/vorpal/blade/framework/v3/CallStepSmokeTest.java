package org.vorpal.blade.framework.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

/// Smoke-test driver for the v3 [Callflow] source-line capture (the tier-3
/// instrumentation for the Callflow Viewer). Run via `main`, like the other v3
/// smoke tests.
///
/// Proves — WITHOUT a SIP container — that [Callflow#captureStep] pins the exact
/// call site of an outbound message: (1) a direct call records the calling method
/// and a real line; (2) a call from inside a lambda (the case that matters — a
/// `sendRequest` inside a response lambda) records the synthetic lambda method
/// and its own line, not the framework's; (3) the framework's own
/// `v3.Callflow`/stack frames are never mistaken for the call site; (4) kind and
/// label are carried through.
public final class CallStepSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testDirectCallSite();
		testLambdaCallSite();
		testDistinctLines();
		summary();
	}

	/// A concrete v3 callflow used only to reach the package-visible captureStep
	/// from realistic call sites (a method body and a lambda body).
	static final class Probe extends Callflow {
		private static final long serialVersionUID = 1L;

		@Override
		public void process(SipServletRequest request) throws ServletException, IOException {
			// unused — the probe never actually runs a call
		}

		CallStep fromMethod() {
			return captureStep("sess-1", 1, "request", "INVITE", "INVITE sip:bob@example.com SIP/2.0\r\nVia: x\r\n");
		}

		CallStep fromLambda() {
			CallStep[] holder = new CallStep[1];
			Runnable r = () -> holder[0] = captureStep("sess-1", 2, "response", "200", null);
			r.run();
			return holder[0];
		}
	}

	private static void testDirectCallSite() {
		CallStep s = new Probe().fromMethod();
		check("direct: class is the concrete callflow", s.getClassName().endsWith("Probe"));
		check("direct: method is the caller (fromMethod)", "fromMethod".equals(s.getMethodName()));
		check("direct: a real source line was captured", s.getLine() > 0);
		check("direct: kind/label carried", "request".equals(s.getKind()) && "INVITE".equals(s.getLabel()));
		check("direct: direction is out", CallStep.OUT.equals(s.getDirection()));
		check("direct: message text carried", s.getMessage() != null && s.getMessage().startsWith("INVITE sip:bob"));
	}

	private static void testLambdaCallSite() {
		CallStep s = new Probe().fromLambda();
		check("lambda: class is the concrete callflow", s.getClassName().endsWith("Probe"));
		// A lambda body compiles to a synthetic method like lambda$fromLambda$0.
		check("lambda: method is the lambda body, not a framework frame",
				s.getMethodName().contains("lambda"));
		check("lambda: a real source line was captured", s.getLine() > 0);
		check("lambda: kind/label carried", "response".equals(s.getKind()) && "200".equals(s.getLabel()));
		check("lambda: null message tolerated", s.getMessage() == null);
	}

	private static void testDistinctLines() {
		CallStep a = new Probe().fromMethod();
		CallStep b = new Probe().fromLambda();
		check("two different call sites capture two different lines", a.getLine() != b.getLine());
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
		System.out.println("CallStepSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
