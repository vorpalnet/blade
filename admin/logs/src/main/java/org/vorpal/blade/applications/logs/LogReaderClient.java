package org.vorpal.blade.applications.logs;

import java.util.Set;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.vorpal.blade.framework.logs.LogFileInfo;
import org.vorpal.blade.framework.logs.LogSlice;
import org.vorpal.blade.framework.logs.VorpalLogReaderMXBean;

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

	public static VorpalLogReaderMXBean forServer(String serverName) throws Exception {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName pattern = new ObjectName("vorpal.blade:Type=LogReader,Name=" + serverName + ",*");
			Set<ObjectName> matches = mbs.queryNames(pattern, null);
			if (matches.isEmpty()) {
				throw new IllegalStateException(
					"No LogReader MBean for server '" + serverName + "'. " +
					"Ensure the BLADE shared library is deployed there and at least one BLADE WAR has started.");
			}
			ObjectName on = matches.iterator().next();
			return JMX.newMXBeanProxy(mbs, on, VorpalLogReaderMXBean.class);
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
}
