package org.vorpal.blade.services.options;

import java.lang.reflect.Method;

/// The way to `blade-overload.jar`'s verdict holder in the domain `lib/`
/// folder, by reflection. The JAR is on the server classpath or it is not,
/// and that cannot change without a restart, so the lookup runs once.
///
/// Reflection, not a compile-time dependency: a copy of the class inside this
/// WAR would be a different class from the server's, and a verdict set on it
/// would reach nobody.
final class OverloadBridge {

	static final String HOLDER = "org.vorpal.blade.overload.Overload";

	private static volatile boolean looked;
	private static Method report;
	private static Method consulted;

	private OverloadBridge() {
	}

	private static synchronized void find() {
		if (looked) {
			return;
		}
		try {
			Class<?> holder = Class.forName(HOLDER, true, OverloadBridge.class.getClassLoader());
			report = holder.getMethod("report", boolean.class, long.class);
			consulted = holder.getMethod("consulted");
		} catch (Throwable t) {
			report = null;
			consulted = null;
		}
		looked = true;
	}

	/// True when `blade-overload.jar` is in the domain `lib/` folder.
	static boolean installed() {
		find();
		return report != null;
	}

	/// Hands OCCAS the verdict, good for `validMillis`. Does nothing when the
	/// JAR is not installed.
	static void report(boolean refuse, long validMillis) {
		find();
		if (report == null) {
			return;
		}
		try {
			report.invoke(null, refuse, validMillis);
		} catch (Throwable t) {
			// the verdict expires on its own
		}
	}

	/// When OCCAS last asked for the verdict, or 0 if never (or not installed).
	static long consulted() {
		find();
		if (consulted == null) {
			return 0;
		}
		try {
			return (Long) consulted.invoke(null);
		} catch (Throwable t) {
			return 0;
		}
	}
}
