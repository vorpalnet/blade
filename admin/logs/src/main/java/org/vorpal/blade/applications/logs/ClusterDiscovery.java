package org.vorpal.blade.applications.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/// Enumerates servers in the domain via the WebLogic DomainConfiguration MBean.
/// Adapts the JMX walk used by `admin/tuning/.../ClusterSettings.java` so the
/// log-viewer can offer the same set of servers an operator already sees in
/// the Performance/Tuning console.
public class ClusterDiscovery {

	private static final Logger log = Logger.getLogger(ClusterDiscovery.class.getName());

	public static class ServerInfo {
		public final String name;
		public final String listenAddress;
		public final int listenPort;
		public final String cluster;

		public ServerInfo(String name, String listenAddress, int listenPort, String cluster) {
			this.name = name;
			this.listenAddress = listenAddress;
			this.listenPort = listenPort;
			this.cluster = cluster;
		}
	}

	public static List<ServerInfo> listServers() throws Exception {
		List<ServerInfo> out = new ArrayList<>();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			// Canonical WLS bootstrap: DomainRuntimeServiceMBean is the
			// well-known entry point exposed on the federated DomainRuntime
			// MBeanServer. From it we navigate to the (named) DomainMBean
			// that holds Servers/Clusters.
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domain = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			if (domain == null) {
				throw new IllegalStateException("DomainRuntimeService.DomainConfiguration is null");
			}
			ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domain, "Servers");
			log.info("ClusterDiscovery: domain=" + domain + ", Servers="
					+ (servers == null ? "null" : servers.length));
			if (servers == null) return out;
			for (ObjectName server : servers) {
				String name = strAttr(mbs, server, "Name");
				String listen = strAttr(mbs, server, "ListenAddress");
				int port = intAttr(mbs, server, "ListenPort", 0);
				ObjectName clusterRef = onAttr(mbs, server, "Cluster");
				String clusterName = clusterRef != null ? strAttr(mbs, clusterRef, "Name") : "";
				out.add(new ServerInfo(name, listen, port, clusterName));
			}
		}
		return out;
	}

	private static String strAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static int intAttr(MBeanServer mbs, ObjectName on, String name, int dflt) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? ((Number) v).intValue() : dflt;
		} catch (Exception e) {
			return dflt;
		}
	}

	private static ObjectName onAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			return (ObjectName) mbs.getAttribute(on, name);
		} catch (Exception e) {
			return null;
		}
	}
}
