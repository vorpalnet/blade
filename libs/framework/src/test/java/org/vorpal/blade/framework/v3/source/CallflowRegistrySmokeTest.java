package org.vorpal.blade.framework.v3.source;

import javax.servlet.sip.SipServletRequest;

/// Container-free smoke test: constructing any v3 callflow registers its
/// concrete class in this app's [CallflowRegistry] (the `used` signal the
/// Source manifest and the viewer's gallery rely on).
/// Run: `java -cp <classes:test-classes:deps> org.vorpal.blade.framework.v3.source.CallflowRegistrySmokeTest`
public class CallflowRegistrySmokeTest {

	private static int passed = 0;
	private static int failed = 0;

	/// Stands in for a framework callflow an app instantiates (InitialInvite).
	static class ProbeFlow extends org.vorpal.blade.framework.v3.Callflow {
		private static final long serialVersionUID = 1L;

		@Override
		public void process(SipServletRequest request) {
		}
	}

	public static void main(String[] args) {
		check("unseen class not registered", !CallflowRegistry.contains(ProbeFlow.class.getName()));

		new ProbeFlow();
		check("construction self-registers", CallflowRegistry.contains(ProbeFlow.class.getName()));

		new ProbeFlow();
		check("repeat construction is a no-op", CallflowRegistry.contains(ProbeFlow.class.getName()));

		check("null-safe record", nullSafe());
		check("null-safe contains", !CallflowRegistry.contains(null));

		System.out.println("CallflowRegistrySmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	private static boolean nullSafe() {
		try {
			CallflowRegistry.record(null);
			return true;
		} catch (Exception e) {
			return false;
		}
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
}
