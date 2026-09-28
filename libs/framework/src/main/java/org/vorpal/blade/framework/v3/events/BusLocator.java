package org.vorpal.blade.framework.v3.events;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.naming.InitialContext;

/// Finds the servers that host the event bus, for an application whose own
/// server does not: in practice, one on the AdminServer.
///
/// The bus's topic is bound only on the servers that host its members, the
/// engine cluster's. So this asks the domain which servers are running
/// ([#DOMAIN_RUNTIME], which exists only on the AdminServer), and keeps every
/// one whose own naming directory has the topic. Nothing names a cluster: a
/// media tier or any other server without the topic simply drops out.
///
/// The result is a WebLogic provider URL listing all of them
/// (`t3://engine0:8001,t3://engine1:8001`), so a lookup fails over between
/// engines on its own. Its cost is one short-timeout lookup per running server,
/// paid when the bus is located, not per event.
final class BusLocator {

	static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";

	private static final String DOMAIN_RUNTIME_SERVICE = "com.bea:Name=DomainRuntimeService,"
			+ "Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";

	/// How long a server gets to answer, so a hung one cannot stall the caller.
	private static final String TIMEOUT_MS = "5000";

	private BusLocator() {
	}

	/// The provider URL of every running server, other than this one, where
	/// `destinationJndi` resolves; null when there is none or this server cannot
	/// see the domain (an engine, which does not need this).
	static String discover(String destinationJndi) {
		List<String> hosts = new ArrayList<>();
		try {
			InitialContext local = new InitialContext();
			MBeanServer domain;
			try {
				domain = (MBeanServer) local.lookup(DOMAIN_RUNTIME);
			} finally {
				local.close();
			}
			String self = System.getProperty("weblogic.Name");
			ObjectName[] servers = (ObjectName[]) domain.getAttribute(new ObjectName(DOMAIN_RUNTIME_SERVICE),
					"ServerRuntimes");
			for (ObjectName server : servers) {
				String name = (String) domain.getAttribute(server, "Name");
				if (name == null || name.equals(self)) {
					continue;
				}
				String url = (String) domain.invoke(server, "getURL", new Object[] { "t3" },
						new String[] { String.class.getName() });
				if (url != null && hosts(url, destinationJndi)) {
					hosts.add(url);
				}
			}
		} catch (Exception notTheAdminServer) {
			return null;
		}
		return hosts.isEmpty() ? null : String.join(",", hosts);
	}

	/// Whether `destinationJndi` resolves in `url`'s naming directory.
	private static boolean hosts(String url, String destinationJndi) {
		Hashtable<String, String> env = new Hashtable<>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
		env.put(Context.PROVIDER_URL, url);
		env.put("weblogic.jndi.connectTimeout", TIMEOUT_MS);
		env.put("weblogic.jndi.responseReadTimeout", TIMEOUT_MS);
		try {
			InitialContext remote = new InitialContext(env);
			try {
				remote.lookup(destinationJndi);
				return true;
			} finally {
				remote.close();
			}
		} catch (Exception notHere) {
			return false;
		}
	}
}
