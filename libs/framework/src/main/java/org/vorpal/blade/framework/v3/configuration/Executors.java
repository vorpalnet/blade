package org.vorpal.blade.framework.v3.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/// Framework-level executors for blocking work that must not run on
/// the SIP container thread.
///
/// The [#DB] executor backs [org.vorpal.blade.framework.v3.configuration.adapters.JdbcAdapter]
/// and [org.vorpal.blade.framework.v3.configuration.adapters.LdapAdapter]
/// — both are synchronous under the hood (JDBC's `Statement.execute`,
/// JNDI LDAP's `DirContext.search`), so they run off the SIP thread
/// on a bounded pool sized to roughly match typical JDBC connection
/// pools.
///
/// Tunable via `-Dvorpal.blade.db.executor.threads=N`; default 50.
public final class Executors {

	private static final int DB_THREADS =
			Integer.getInteger("vorpal.blade.db.executor.threads", 50);

	/// Bounded executor for JDBC / LDAP blocking work. Daemon threads
	/// so they don't keep the JVM alive on shutdown.
	public static final ExecutorService DB = java.util.concurrent.Executors.newFixedThreadPool(
			DB_THREADS, new NamedDaemonThreadFactory("blade-db"));

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
