package org.vorpal.blade.framework.v3.analytics;

/// The v3 face of [org.vorpal.blade.framework.v2.analytics.Analytics].
///
/// The implementation lives in the frozen v2 class (unchanged); this is the name
/// a v3 application binds to so its imports stay `v3.*`. The static accessors
/// (e.g. the per-thread request holder) are inherited, so `Analytics.<static>`
/// resolves through this face; instances construct with `new Analytics()`.
public class Analytics extends org.vorpal.blade.framework.v2.analytics.Analytics {

	private static final long serialVersionUID = 1L;

	public Analytics() {
		super();
	}
}
