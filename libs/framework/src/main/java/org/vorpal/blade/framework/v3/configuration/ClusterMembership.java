package org.vorpal.blade.framework.v3.configuration;

import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;

/// Which cluster each server of the domain is in, read from the domain
/// configuration MBeans on the AdminServer. A static server names its
/// cluster (`ServerMBean.Cluster`); a dynamic cluster lists the servers it
/// calculates (`DynamicServersMBean.DynamicServerNames`). A server in no
/// cluster, such as the AdminServer, is absent.
///
/// The settings loader on each server finds its own cluster the same way, so
/// an editor that uses this merges a server's files in the order that server
/// does: domain, its cluster, the server.
public final class ClusterMembership {

	/// The domain runtime service, the root of the domain configuration tree.
	public static final String DOMAIN_RUNTIME_SERVICE = "com.bea:Name=DomainRuntimeService,"
			+ "Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";

	private ClusterMembership() {
	}

	/// Server name to cluster name, read through the AdminServer's
	/// `java:comp/env/jmx/domainRuntime`. Empty off the AdminServer or when
	/// the read fails.
	public static Map<String, String> read() {
		try {
			return of(domainRuntime());
		} catch (Exception e) {
			return new HashMap<>();
		}
	}

	/// The AdminServer's domain-runtime MBean server
	/// (`java:comp/env/jmx/domainRuntime`); throws off the AdminServer.
	public static MBeanServer domainRuntime() throws javax.naming.NamingException {
		InitialContext ctx = new InitialContext();
		try {
			return (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
		} finally {
			ctx.close();
		}
	}

	/// Server name to cluster name, from the domain runtime MBean server `dr`.
	/// What was read before a failure stands: for a display, partial is
	/// better than none.
	public static Map<String, String> of(MBeanServer dr) {
		Map<String, String> out = new HashMap<>();
		try {
			fill(dr, out);
		} catch (Exception e) {
			// Partial is better than none: what was read so far stands.
		}
		return out;
	}

	/// Server name to cluster name for an editor about to save `app`'s
	/// file: empty when `app` has no cluster or server file, so membership
	/// cannot matter; else read whole, or an exception saying why not. A
	/// partial read would check a server's file without its cluster's.
	public static Map<String, String> forSave(String app) throws java.io.IOException {
		if (ConfigFiles.of(app, new HashMap<>()).size() <= 1) return new HashMap<>();
		Map<String, String> out = new HashMap<>();
		try {
			fill(domainRuntime(), out);
		} catch (Exception e) {
			throw new java.io.IOException("cannot read which cluster each server is in, so the cluster and server files cannot be checked: " + e, e);
		}
		return out;
	}

	private static void fill(MBeanServer dr, Map<String, String> out) throws Exception {
		ObjectName config = (ObjectName) dr.getAttribute(new ObjectName(DOMAIN_RUNTIME_SERVICE), "DomainConfiguration");
		if (config == null) throw new IllegalStateException("no DomainConfiguration");
		for (ObjectName s : (ObjectName[]) dr.getAttribute(config, "Servers")) {
			Object c = dr.getAttribute(s, "Cluster");
			if (c instanceof ObjectName) out.put(String.valueOf(dr.getAttribute(s, "Name")), String.valueOf(dr.getAttribute((ObjectName) c, "Name")));
		}
		for (ObjectName c : (ObjectName[]) dr.getAttribute(config, "Clusters")) {
			Object dyn = get(dr, c, "DynamicServers");
			Object names = (dyn instanceof ObjectName) ? dr.getAttribute((ObjectName) dyn, "DynamicServerNames") : null;
			if (names instanceof String[]) {
				for (String n : (String[]) names) out.put(n, String.valueOf(dr.getAttribute(c, "Name")));
			}
		}
	}

	private static Object get(MBeanServer mbs, ObjectName on, String attr) {
		try {
			return mbs.getAttribute(on, attr);
		} catch (Exception e) {
			return null;
		}
	}
}
