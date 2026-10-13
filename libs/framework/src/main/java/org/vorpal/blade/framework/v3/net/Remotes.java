package org.vorpal.blade.framework.v3.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The services this application calls off the SIP path, as it has found
/// them: per remote, how many calls, how many failed, how many failed in a
/// row, the last failure and when, and whether a circuit breaker has it cut
/// off.
///
/// **Every outbound call reports here**, whichever framework class makes it:
/// the iRouter connectors (`rest`, `jdbc`, `ldap`), an OAuth token fetch
/// (`oauth`), the sign-in provider (`oidc`), a media session lost (`media`).
/// An application's own client code can report the same way.
///
/// **Per application, per node.** The framework JAR is in every WAR, so this
/// state belongs to one application on one server; [RemotesControl] exposes
/// it as an MBean, and a dashboard reading the domain sees every
/// application's view side by side, as the balancer's endpoint health is
/// read. A breaker opening or closing is also published
/// ([BladeEventTypes#REMOTE_DOWN], [BladeEventTypes#REMOTE_UP]) so it is seen
/// at once and kept until it recovers.
///
/// At most [#MAX_REMOTES] remotes are kept, so a URL templated per call cannot
/// grow the map without end; a remote past that is not recorded.
public final class Remotes {

	public static final int MAX_REMOTES = 256;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Map<String, Remote> REMOTES = new ConcurrentHashMap<>();

	private Remotes() {
	}

	/// One remote as this application sees it.
	static final class Remote {
		final String kind;
		final String remote;
		final AtomicLong calls = new AtomicLong();
		final AtomicLong failures = new AtomicLong();
		final AtomicLong consecutive = new AtomicLong();
		final AtomicLong millis = new AtomicLong();
		volatile long lastSuccessAt;
		volatile long lastFailureAt;
		volatile String lastFailure;
		volatile boolean open;

		Remote(String kind, String remote) {
			this.kind = kind;
			this.remote = remote;
		}
	}

	private static Remote remote(String kind, String remote) {
		if (kind == null || remote == null || remote.isEmpty()) {
			return null;
		}
		String key = kind + "|" + remote;
		Remote r = REMOTES.get(key);
		if (r == null && REMOTES.size() < MAX_REMOTES) {
			r = REMOTES.computeIfAbsent(key, k -> new Remote(kind, remote));
		}
		return r;
	}

	/// A call to `remote` worked, in `millis`. Never throws.
	public static void succeeded(String kind, String remote, long millis) {
		Remote r = remote(kind, remote);
		if (r != null) {
			r.calls.incrementAndGet();
			r.millis.addAndGet(Math.max(0, millis));
			r.consecutive.set(0);
			r.lastSuccessAt = System.currentTimeMillis();
		}
	}

	/// A call to `remote` failed: `reason` in a few words (`HTTP 503`,
	/// `connect timed out`). Never throws.
	public static void failed(String kind, String remote, String reason, long millis) {
		Remote r = remote(kind, remote);
		if (r != null) {
			r.calls.incrementAndGet();
			r.failures.incrementAndGet();
			r.millis.addAndGet(Math.max(0, millis));
			r.consecutive.incrementAndGet();
			r.lastFailureAt = System.currentTimeMillis();
			r.lastFailure = reason;
		}
	}

	/// A circuit breaker cut `remote` off (`open`) or let it back in. Called on
	/// the edge only, so each outage publishes one down and one up. Never
	/// throws.
	public static void breaker(String kind, String remote, boolean open, String reason) {
		Remote r = remote(kind, remote);
		if (r != null) {
			r.open = open;
		}
		Events.publish(open ? BladeEventTypes.REMOTE_DOWN : BladeEventTypes.REMOTE_UP, remote, data -> data
				.put("kind", kind)
				.put("remote", remote)
				.put("reason", reason)
				.put("application", SettingsManager.getApplicationName())
				.put("node", SettingsManager.getServerName()));
	}

	/// The host, with its port when one is given, of a URL or URI; the input
	/// itself when it has no host part (a JNDI name).
	public static String hostOf(String url) {
		if (url == null) {
			return null;
		}
		try {
			java.net.URI u = java.net.URI.create(url.trim());
			if (u.getHost() != null) {
				return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
			}
		} catch (IllegalArgumentException notAUri) {
			// fall through
		}
		return url;
	}

	/// A failure in a few words: the innermost cause's class and message, which
	/// is where `Connection refused` or `connect timed out` is, not the
	/// wrapper's "request failed".
	public static String reason(Throwable t) {
		Throwable root = t;
		while (root != null && root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		if (root == null) {
			return "failed";
		}
		String m = root.getMessage();
		String r = root.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
		return r.length() > 160 ? r.substring(0, 160) : r;
	}

	/// Every remote, for [RemotesControl].
	static String json() {
		ArrayNode out = MAPPER.createArrayNode();
		for (Remote r : REMOTES.values()) {
			ObjectNode o = out.addObject();
			o.put("kind", r.kind);
			o.put("remote", r.remote);
			long calls = r.calls.get();
			o.put("calls", calls);
			o.put("failures", r.failures.get());
			o.put("consecutive", r.consecutive.get());
			o.put("avgMs", calls == 0 ? 0 : r.millis.get() / calls);
			o.put("lastSuccessAt", r.lastSuccessAt);
			o.put("lastFailureAt", r.lastFailureAt);
			if (r.lastFailure != null) {
				o.put("lastFailure", r.lastFailure);
			}
			o.put("open", r.open);
		}
		return out.toString();
	}

	/// For tests: forget every remote.
	static void clear() {
		REMOTES.clear();
	}
}
