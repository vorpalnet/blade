package org.vorpal.blade.framework.v3.events;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Tells the dashboard about a problem an operator should fix, and where it is
/// fixed: an application that knows its own trouble (a pool it cannot fill, a
/// certificate about to expire, a peer it cannot reach) raises it with a link
/// to the admin page for it, and clears it when it is over. The dashboard
/// shows it under Issues without knowing the application.
///
/// ```java
/// Problems.raise("cert:sip.example.net", "TLS certificate expires in 6 days",
/// 		"/blade/configurator/configurator.html?schema=gateway");
/// Problems.clear("cert:sip.example.net");
/// ```
///
/// Call on the change, not on every check: each call is an event on the bus.
/// A problem is per node, so the same key raised on two servers is two issues.
public final class Problems {

	private Problems() {
	}

	/// Raises, or restates, the problem under `key`. `open` must be a path under
	/// `/blade/`; anything else is dropped, so the issue shows without a link.
	public static void raise(String key, String summary, String open) {
		String link = (open != null && open.startsWith("/blade/") && !open.startsWith("/blade//")) ? open : null;
		Events.publish(BladeEventTypes.PROBLEM_RAISED, key, data -> data
				.put("key", key)
				.put("summary", summary)
				.put("open", link)
				.put("application", SettingsManager.getApplicationName())
				.put("node", SettingsManager.getServerName()));
	}

	/// The problem under `key` is over.
	public static void clear(String key) {
		Events.publish(BladeEventTypes.PROBLEM_CLEARED, key, data -> data
				.put("key", key)
				.put("application", SettingsManager.getApplicationName())
				.put("node", SettingsManager.getServerName()));
	}
}
