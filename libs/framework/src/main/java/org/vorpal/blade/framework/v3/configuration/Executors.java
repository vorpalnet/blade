package org.vorpal.blade.framework.v3.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/// Framework-level executors for blocking work that must not run on
/// the SIP container thread.
///
/// The [#DB] executor backs [org.vorpal.blade.framework.v3.configuration.connectors.JdbcConnector]
/// and [org.vorpal.blade.framework.v3.configuration.connectors.LdapConnector]
/// — both are synchronous under the hood (JDBC's `Statement.execute`,
/// JNDI LDAP's `DirContext.search`), so they run off the SIP thread
/// on a bounded pool sized to roughly match typical JDBC connection
/// pools.
///
/// Tunable via `-Dvorpal.blade.db.executor.threads=N` (default 50) and
/// `-Dvorpal.blade.db.executor.queue=N` (default 1000).
///
/// The queue is bounded too. With a slow database every waiting lookup holds a
/// request and its session, so an unbounded queue under a call flood grows
/// until the heap goes. When it is full a new lookup is refused at once
/// ([java.util.concurrent.RejectedExecutionException]); the connector's error
/// handling lets the call continue to its default route.
public final class Executors {

	private static final int DB_THREADS =
			Integer.getInteger("vorpal.blade.db.executor.threads", 50);

	private static final int DB_QUEUE =
			Integer.getInteger("vorpal.blade.db.executor.queue", 1000);

	/// Bounded executor for JDBC / LDAP blocking work. Daemon threads
	/// so they don't keep the JVM alive on shutdown.
	public static final ExecutorService DB = new java.util.concurrent.ThreadPoolExecutor(
			DB_THREADS, DB_THREADS, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
			new java.util.concurrent.ArrayBlockingQueue<>(DB_QUEUE), new NamedDaemonThreadFactory("blade-db"),
			new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

	private Executors() {
	}

	private static final class NamedDaemonThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicInteger n = new AtomicInteger();

		NamedDaemonThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	}
}
