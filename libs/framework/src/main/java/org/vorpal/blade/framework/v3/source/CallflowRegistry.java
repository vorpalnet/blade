package org.vorpal.blade.framework.v3.source;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// The per-app record of which callflow classes have actually RUN here.
///
/// `v3.Callflow`'s constructor calls [#record] on every construction, so the
/// moment an app executes `new InitialInvite(this)` — from `chooseCallflow`,
/// a timer, anywhere — `InitialInvite` is registered as "used by this app".
/// That answers a question no static analysis can: which of the framework's
/// bundled callflows does THIS app really use? (Dispatch is imperative code in
/// `chooseCallflow`; there is nothing to read.) The [Source] manifest merges
/// this with the app's own-package callflows into its `used` flag, which is
/// what the Callflow Viewer's gallery shows per service.
///
/// Statics are per-WAR (the framework JAR is bundled in every WAR — same
/// idiom as `TraceLog`), so each app keeps its own registry and nothing is
/// shared across apps or nodes. In-memory only: after a restart it refills as
/// traffic flows, which is the honest semantics for "observed in use".
public final class CallflowRegistry {

	private static final Set<String> used = ConcurrentHashMap.newKeySet();

	private CallflowRegistry() {
	}

	/// Record a callflow class as used by this app. One cheap set-add per
	/// callflow construction; a repeat add is a no-op.
	public static void record(Class<?> callflowClass) {
		if (callflowClass != null) {
			used.add(callflowClass.getName());
		}
	}

	public static boolean contains(String className) {
		return className != null && used.contains(className);
	}
}
