package org.vorpal.blade.framework.v2.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/// Admin-tier helper for finding a BLADE app's [Settings] Configuration
/// MBeans and telling them to reload — the "Publish" half of the
/// save-then-publish flow (Save writes the config file; Publish makes the
/// running nodes pick it up).
///
/// Extracted from the Configurator, where this lookup + reload was
/// duplicated across `FileManagerServlet`, `ValidationAPI`, and
/// `ConfigurationMonitor`. Any AdminServer-hosted WAR (Configurator, Flow,
/// CRUD rules editor, …) can use it; it requires the
/// `java:comp/env/jmx/domainRuntime` JNDI binding, which only exists on the
/// AdminServer — this is not for engine-tier code.
public final class ConfigPublisher {

	private ConfigPublisher() {
	}

	/// The domain-runtime MBeanServer connection, reaching every managed
	/// server's MBeans from the AdminServer.
	public static MBeanServer domainRuntimeMBeanServer() throws NamingException {
		InitialContext ctx = new InitialContext();
		try {
			return (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
		} finally {
			ctx.close();
		}
	}

	/// Every Configuration MBean registered for `appName`
	/// (`vorpal.blade:Name=<app>,Type=Configuration,*`) — one per node
	/// hosting the app — as proxies keyed by ObjectName. Empty map when the
	/// app isn't deployed anywhere.
	public static Map<ObjectName, SettingsMXBean> configurationMBeans(MBeanServer mbeanServer, String appName)
			throws Exception {
		ObjectName pattern = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration,*");
		Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);
		Map<ObjectName, SettingsMXBean> proxies = new LinkedHashMap<>();
		for (ObjectInstance mbean : mbeans) {
			proxies.put(mbean.getObjectName(),
					JMX.newMXBeanProxy(mbeanServer, mbean.getObjectName(), SettingsMXBean.class));
		}
		return proxies;
	}

	/// Reloads the configuration on every node hosting `appName`. Returns
	/// one action line per reloaded MBean. Throws [IOException] when no
	/// MBean matches — the app isn't deployed, which the caller should
	/// surface rather than report a silent no-op "success".
	public static List<String> reload(String appName) throws Exception {
		MBeanServer mbeanServer = domainRuntimeMBeanServer();
		Map<ObjectName, SettingsMXBean> proxies = configurationMBeans(mbeanServer, appName);
		if (proxies.isEmpty()) {
			throw new IOException("No Configuration MBean found for application: " + appName);
		}
		List<String> actions = new ArrayList<>();
		for (Map.Entry<ObjectName, SettingsMXBean> e : proxies.entrySet()) {
			e.getValue().reload();
			actions.add("Reloaded " + e.getKey());
		}
		return actions;
	}
}
