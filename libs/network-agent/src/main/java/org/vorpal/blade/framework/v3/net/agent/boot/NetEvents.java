package org.vorpal.blade.framework.v3.net.agent.boot;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/// What applications' own network code found, recorded by the BLADE agent:
/// the connections they opened and the HTTP answers they got, per
/// application and remote.
///
/// **On the boot class path.** The agent rewrites JDK classes (`Socket`,
/// `SocketChannel`, `HttpURLConnection`, `HttpClient`) to call these methods,
/// and JDK classes see only what the boot loader loads. So this class uses
/// nothing outside `java.base`, and every method is static and never throws
/// into the code it was called from.
///
/// **Credited to the application whose code made the call.** An application
/// registers its classloader ([#register], the framework's `RemotesControl`
/// does it at start); a call is recorded for the registered loader its
/// thread's context classloader descends from, and not at all for a thread
/// that belongs to no application. Calls the container makes for itself (the
/// SIP transport, JMS, the JDBC pools), and calls the BLADE framework already
/// records, are left out by looking at who is on the stack, which is done only
/// for a connect or an HTTP exchange, never per byte.
public final class NetEvents {

	/// At most this many remotes per application, so a URL built per call
	/// cannot grow the map without end.
	public static final int MAX_REMOTES = 256;

	/// Callers on the stack whose network use is someone else's to report: the
	/// container's own transports and pools, the JDBC driver (pools are watched
	/// on their own), and the framework classes that report to `Remotes`.
	private static final String[] NOT_OURS = { "com.bea.wcp.sip.", "com.bea.wcp.", "weblogic.jdbc.", "weblogic.jms.",
			"weblogic.messaging.", "weblogic.rjvm.", "weblogic.socket.", "weblogic.server.", "weblogic.cluster.",
			"oracle.jdbc.", "oracle.net.", "org.vorpal.blade.framework.v3.configuration.connectors.",
			"org.vorpal.blade.framework.v3.configuration.auth.", "org.vorpal.blade.framework.v3.security.OidcProvider" };

	/// The HTTP clients that report their own exchanges, so a connect made
	/// inside one is not reported twice.
	private static final String[] HTTP_CLIENT = { "jdk.internal.net.http.", "java.net.http." };

	private static final Map<ClassLoader, App> APPS = new WeakHashMap<>();
	private static final Map<Object, Pending> PENDING = new WeakHashMap<>();
	private static final Map<Object, Boolean> REPORTED = new WeakHashMap<>();

	private NetEvents() {
	}

	// ── what an application holds ───────────────────────────────────────────

	static final class App {
		final String name;
		final Map<String, Rec> recs = new ConcurrentHashMap<>();

		App(String name) {
			this.name = name;
		}

		Rec rec(String kind, String remote) {
			String key = kind + "|" + remote;
			Rec r = recs.get(key);
			if (r == null && recs.size() < MAX_REMOTES) {
				r = recs.computeIfAbsent(key, k -> new Rec(kind, remote));
			}
			return r;
		}
	}

	static final class Rec {
		final String kind;
		final String remote;
		final AtomicLong calls = new AtomicLong();
		final AtomicLong failures = new AtomicLong();
		final AtomicLong consecutive = new AtomicLong();
		final AtomicLong millis = new AtomicLong();
		volatile long lastSuccessAt;
		volatile long lastFailureAt;
		volatile String lastFailure;

		Rec(String kind, String remote) {
			this.kind = kind;
			this.remote = remote;
		}

		void ok(long ms) {
			calls.incrementAndGet();
			millis.addAndGet(Math.max(0, ms));
			consecutive.set(0);
			lastSuccessAt = System.currentTimeMillis();
		}

		void failed(String reason, long ms) {
			calls.incrementAndGet();
			failures.incrementAndGet();
			millis.addAndGet(Math.max(0, ms));
			consecutive.incrementAndGet();
			lastFailureAt = System.currentTimeMillis();
			lastFailure = reason;
		}
	}

	/// A non-blocking connect not finished yet: who asked, where to, since when.
	static final class Pending {
		final App app;
		final String remote;
		final long start;

		Pending(App app, String remote, long start) {
			this.app = app;
			this.remote = remote;
			this.start = start;
		}
	}

	// ── applications ─────────────────────────────────────────────────────────

	/// An application starts: calls made on threads whose context classloader
	/// is `loader`, or descends from it, are recorded as `name`'s.
	public static void register(ClassLoader loader, String name) {
		if (loader != null) {
			synchronized (APPS) {
				APPS.put(loader, new App(name));
			}
		}
	}

	/// An application stops: forget it, so its loader is never held.
	public static void unregister(ClassLoader loader) {
		if (loader != null) {
			synchronized (APPS) {
				APPS.remove(loader);
			}
		}
	}

	/// What `loader`'s application has recorded, as a JSON array in the shape
	/// of the framework's `Remotes`, each entry marked `"agent":true`.
	public static String json(ClassLoader loader) {
		App app;
		synchronized (APPS) {
			app = loader == null ? null : APPS.get(loader);
		}
		StringBuilder out = new StringBuilder("[");
		if (app != null) {
			boolean first = true;
			for (Rec r : app.recs.values()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				long calls = r.calls.get();
				out.append("{\"kind\":").append(quote(r.kind)).append(",\"remote\":").append(quote(r.remote))
						.append(",\"calls\":").append(calls).append(",\"failures\":").append(r.failures.get())
						.append(",\"consecutive\":").append(r.consecutive.get()).append(",\"avgMs\":")
						.append(calls == 0 ? 0 : r.millis.get() / calls).append(",\"lastSuccessAt\":")
						.append(r.lastSuccessAt).append(",\"lastFailureAt\":").append(r.lastFailureAt);
				if (r.lastFailure != null) {
					out.append(",\"lastFailure\":").append(quote(r.lastFailure));
				}
				out.append(",\"open\":false,\"agent\":true}");
			}
		}
		return out.append(']').toString();
	}

	// ── the rewritten JDK methods call these ─────────────────────────────────

	/// `Socket.connect(endpoint, timeout)` returned or threw.
	public static void socketConnect(SocketAddress endpoint, Throwable thrown, long start) {
		try {
			App app = caller(true);
			if (app != null) {
				tcp(app, remote(endpoint), thrown, start);
			}
		} catch (Throwable ignore) {
			// never into the caller's connect
		}
	}

	/// `SocketChannel.connect(endpoint)` returned `connected` or threw. A
	/// non-blocking connect that has not finished is completed by
	/// [#channelFinish].
	public static void channelConnect(Object channel, SocketAddress endpoint, boolean connected, Throwable thrown,
			long start) {
		try {
			App app = caller(true);
			if (app == null) {
				return;
			}
			String remote = remote(endpoint);
			if (thrown != null || connected) {
				tcp(app, remote, thrown, start);
			} else {
				synchronized (PENDING) {
					PENDING.put(channel, new Pending(app, remote, start));
				}
			}
		} catch (Throwable ignore) {
			// never into the caller's connect
		}
	}

	/// `SocketChannel.finishConnect()` returned `done` or threw.
	public static void channelFinish(Object channel, boolean done, Throwable thrown) {
		try {
			if (!done && thrown == null) {
				return;
			}
			Pending p;
			synchronized (PENDING) {
				p = PENDING.remove(channel);
			}
			if (p != null) {
				tcp(p.app, p.remote, thrown, p.start);
			}
		} catch (Throwable ignore) {
			// never into the caller's connect
		}
	}

	/// `HttpURLConnection.getInputStream()` returned or threw, with the
	/// response code the connection holds (-1 when no response came). Reported
	/// once per connection, however often the stream is asked for.
	public static void httpUrlConnection(Object connection, URL url, int status, Throwable thrown, long start) {
		try {
			// Neither a status nor a failure: an inner call returned before the
			// exchange finished. The outer call reports it.
			if (status < 0 && thrown == null) {
				return;
			}
			synchronized (REPORTED) {
				if (REPORTED.put(connection, Boolean.TRUE) != null) {
					return;
				}
			}
			App app = caller(false);
			if (app == null) {
				return;
			}
			// A connect that failed was reported by the socket underneath.
			if (status < 0 && isConnectFailure(thrown)) {
				return;
			}
			// An error status leaves getInputStream before the code is kept in
			// the field; the connection answers getResponseCode from what it
			// already read (a call that re-enters here is already REPORTED).
			if (status < 0 && thrown != null && connection instanceof java.net.HttpURLConnection) {
				try {
					status = ((java.net.HttpURLConnection) connection).getResponseCode();
				} catch (Throwable unread) {
					// no response came: leave it -1
				}
			}
			http(app, remote(url), status, thrown, start);
		} catch (Throwable ignore) {
			// never into the caller's request
		}
	}

	/// `HttpClient.sendAsync(...)` returned `future`; its exchange is reported
	/// when the future completes. The caller is looked up now, on the thread
	/// that sent, not on the client's own thread that completes it.
	public static void httpClientSend(Object request, Object future, long start) {
		try {
			if (!(future instanceof CompletableFuture) || request == null) {
				return;
			}
			App app = caller(false);
			if (app != null) {
				// By reflection: java.net.http is loaded by the platform loader,
				// which the boot loader this class lives in cannot see.
				Object uri = api("java.net.http.HttpRequest", "uri").invoke(request);
				String remote = remote(uri instanceof java.net.URI ? (java.net.URI) uri : null);
				((CompletableFuture<?>) future).whenComplete(new Done(app, remote, start));
			}
		} catch (Throwable ignore) {
			// never into the caller's request
		}
	}

	/// Completes a [#httpClientSend]: the response's status, or the failure.
	static final class Done implements BiConsumer<Object, Throwable> {
		final App app;
		final String remote;
		final long start;

		Done(App app, String remote, long start) {
			this.app = app;
			this.remote = remote;
			this.start = start;
		}

		@Override
		public void accept(Object response, Throwable thrown) {
			try {
				int status = -1;
				if (response != null) {
					status = (Integer) api("java.net.http.HttpResponse", "statusCode").invoke(response);
				}
				http(app, remote, status, unwrap(thrown), start);
			} catch (Throwable ignore) {
				// never into the client's completion
			}
		}
	}

	private static final Map<String, java.lang.reflect.Method> API = new ConcurrentHashMap<>();

	/// A method of a public `java.net.http` interface, called on the client's
	/// own implementation objects, whose classes are in a package no caller
	/// may open.
	private static java.lang.reflect.Method api(String type, String method) throws ReflectiveOperationException {
		java.lang.reflect.Method m = API.get(type + "#" + method);
		if (m == null) {
			m = Class.forName(type, false, ClassLoader.getPlatformClassLoader()).getMethod(method);
			API.put(type + "#" + method, m);
		}
		return m;
	}

	// ── recording ────────────────────────────────────────────────────────────

	private static void tcp(App app, String remote, Throwable thrown, long start) {
		Rec r = app.rec("tcp", remote);
		if (r == null) {
			return;
		}
		long ms = (System.nanoTime() - start) / 1_000_000L;
		if (thrown == null) {
			r.ok(ms);
		} else {
			r.failed(reason(thrown), ms);
		}
	}

	/// An HTTP exchange: a 5xx, or no response at all, is a failure; any other
	/// answer means the server is up and working, whatever it thought of the
	/// request.
	private static void http(App app, String remote, int status, Throwable thrown, long start) {
		Rec r = app.rec("http", remote);
		if (r == null) {
			return;
		}
		long ms = (System.nanoTime() - start) / 1_000_000L;
		if (status >= 500) {
			r.failed("HTTP " + status, ms);
		} else if (status > 0) {
			r.ok(ms);
		} else {
			r.failed(thrown == null ? "no response" : reason(thrown), ms);
		}
	}

	/// The registered application the current thread works for, or null when
	/// it works for none, or when the call is someone else's to report.
	private static App caller(boolean socketLevel) {
		App app = null;
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		synchronized (APPS) {
			if (APPS.isEmpty()) {
				return null;
			}
			while (cl != null && app == null) {
				app = APPS.get(cl);
				cl = cl.getParent();
			}
		}
		if (app == null) {
			return null;
		}
		return ours(socketLevel) ? app : null;
	}

	/// Whether the code that made this call is the application's: the first
	/// class on the stack above the JDK decides. Further down, every
	/// application method running in a SIP request has the container beneath
	/// it, so a frame far from the socket says nothing; the nearest one says
	/// who opened it: the container's transport or a pool (not ours), the
	/// framework's own connectors (reported already), or the application and
	/// the libraries it uses (ours). A socket opened inside the JDK's HTTP
	/// client is left to the client, which reports the whole exchange.
	private static boolean ours(boolean socketLevel) {
		return StackWalker.getInstance().walk(frames -> {
			java.util.Iterator<StackWalker.StackFrame> it = frames.iterator();
			while (it.hasNext()) {
				String c = it.next().getClassName();
				if (c.startsWith(BOOT)) {
					continue;
				}
				if (socketLevel) {
					for (String p : HTTP_CLIENT) {
						if (c.startsWith(p)) {
							return false;
						}
					}
				}
				if (isJdk(c)) {
					continue;
				}
				for (String p : NOT_OURS) {
					if (c.startsWith(p)) {
						return false;
					}
				}
				return true;
			}
			return false;
		});
	}

	private static final String BOOT = "org.vorpal.blade.framework.v3.net.agent.boot.";

	private static boolean isJdk(String c) {
		return c.startsWith("java.") || c.startsWith("javax.net.") || c.startsWith("javax.security.")
				|| c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.");
	}


	private static boolean isConnectFailure(Throwable t) {
		Throwable root = unwrap(t);
		if (root == null) {
			return false;
		}
		String m = String.valueOf(root.getMessage()).toLowerCase();
		return root instanceof java.net.ConnectException || root instanceof java.net.UnknownHostException
				|| root instanceof java.net.NoRouteToHostException
				|| root instanceof java.net.SocketTimeoutException && m.contains("connect");
	}

	private static Throwable unwrap(Throwable t) {
		while (t != null && (t instanceof java.util.concurrent.CompletionException
				|| t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	/// The failure in a few words: the first exception that is not a
	/// future's wrapper, which is where `ConnectException` is (its own cause,
	/// a `ClosedChannelException`, says less), with the first message found
	/// down its causes.
	private static String reason(Throwable t) {
		Throwable e = unwrap(t);
		if (e == null) {
			return "failed";
		}
		String m = null;
		for (Throwable c = e; c != null && m == null; c = c.getCause() == c ? null : c.getCause()) {
			m = c.getMessage();
		}
		String r = e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
		return r.length() > 160 ? r.substring(0, 160) : r;
	}

	/// `host:port`, never resolving a name: `getHostString` gives what the
	/// caller asked for, and a reverse lookup on the call path would be a
	/// network call of its own.
	private static String remote(SocketAddress a) {
		if (a instanceof InetSocketAddress) {
			InetSocketAddress i = (InetSocketAddress) a;
			return i.getHostString() + ":" + i.getPort();
		}
		return String.valueOf(a);
	}

	private static String remote(URL url) {
		return url == null ? "?" : url.getPort() > 0 ? url.getHost() + ":" + url.getPort() : url.getHost();
	}

	private static String remote(java.net.URI uri) {
		return uri == null ? "?" : uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
	}

	private static String quote(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
			case '"':
				b.append("\\\"");
				break;
			case '\\':
				b.append("\\\\");
				break;
			default:
				if (c < 0x20) {
					b.append(String.format("\\u%04x", (int) c));
				} else {
					b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}
}
