package org.vorpal.blade.applications.logs;

import java.util.Set;

import javax.management.JMX;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.vorpal.blade.framework.v2.logging.LogFileInfo;
import org.vorpal.blade.framework.v2.logging.LogSearchResult;
import org.vorpal.blade.framework.v2.logging.LogSlice;
import org.vorpal.blade.framework.v2.logging.VorpalLogReaderMXBean;

/// Builds typed JMX proxies for the per-server LogReader MBeans registered by
/// the framework library on every JVM in the domain.
///
/// Lookup uses a wildcard pattern (`...,Name=<server>,*`) rather than an exact
/// ObjectName because WLS DomainRuntime federation appends a `Location=<server>`
/// key when surfacing an MBean from a remote server. The locally-registered
/// AdminServer MBean appears at the bare name; engine1/2/3's MBeans appear with
/// the extra Location key. The wildcard matches both.
public class LogReaderClient {

	private LogReaderClient() {}

	private static ObjectName resolve(MBeanServer mbs, String serverName) throws Exception {
		ObjectName pattern = new ObjectName("vorpal.blade:Type=LogReader,Name=" + serverName + ",*");
		Set<ObjectName> matches = mbs.queryNames(pattern, null);
		if (matches.isEmpty()) {
			throw new IllegalStateException(
				"No LogReader MBean for server '" + serverName + "'. " +
				"Ensure at least one BLADE application has started on that server.");
		}
		return matches.iterator().next();
	}

	public static VorpalLogReaderMXBean forServer(String serverName) throws Exception {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			return JMX.newMXBeanProxy(mbs, resolve(mbs, serverName), VorpalLogReaderMXBean.class);
		}
	}

	/// Whether this server's reader can search.
	///
	/// The reader on a node is created by the first BLADE application to start
	/// in that JVM, and `LogReaderRegistrar` deliberately leaves it registered
	/// when an application stops. Redeploying therefore does NOT replace it —
	/// only restarting the server does. With the framework changing daily, a
	/// node running an older reader than the AdminServer is the normal case,
	/// not an edge case.
	///
	/// So the capability is read off the live MBean's own metadata rather than
	/// assumed from the framework version this WAR happens to carry. That works
	/// against every reader ever registered, including ones built before
	/// `search` existed, which is precisely the population that needs asking.
	public static boolean supportsSearch(String serverName) throws Exception {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			for (MBeanOperationInfo op : mbs.getMBeanInfo(resolve(mbs, serverName)).getOperations()) {
				if ("search".equals(op.getName())) {
					return true;
				}
			}
			return false;
		}
	}

	public static LogFileInfo[] listLogs(String serverName) throws Exception {
		return forServer(serverName).listLogFiles();
	}

	public static LogSlice readSlice(String serverName, String relativePath, long offset, int maxBytes) throws Exception {
		return forServer(serverName).readSlice(relativePath, offset, maxBytes);
	}

	public static LogSlice tail(String serverName, String relativePath, long cursor, int maxBytes) throws Exception {
		return forServer(serverName).tail(relativePath, cursor, maxBytes);
	}

	public static LogSearchResult search(String serverName, String relativePath, String pattern,
			boolean regex, boolean ignoreCase, long fromOffset, int maxMatches, long maxBytesScanned)
			throws Exception {
		return forServer(serverName).search(relativePath, pattern, regex, ignoreCase,
				fromOffset, maxMatches, maxBytesScanned);
	}
}
