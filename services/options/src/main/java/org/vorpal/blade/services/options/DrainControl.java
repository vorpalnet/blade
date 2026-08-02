package org.vorpal.blade.services.options;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// [DrainMXBean] implementation: this node's administrative drain flag.
///
/// Pure per-JVM runtime state — deliberately NOT configuration. There is no
/// file, no overlay, nothing to publish: the switch is the JMX attribute, and
/// it dies with the JVM (a bounced engine comes back undrained and rejoins the
/// load-balancer pool on its next successful ping, exactly like any reboot
/// today). Node health is per-node truth, so a plain volatile is correct here
/// — the same stance as proxy-balancer's `EndpointHealth` ("every node keeps
/// its own independent view").
///
/// ObjectName mirrors the app's Configuration MBean —
/// `vorpal.blade:Name=<app>,Type=Drain[,Cluster=<cluster>]` — so the admin
/// tier can walk engines the same way the Metrics/Trace consoles do (the
/// Domain Runtime MBean Server tags each node's instance with
/// `Location=<server>`).
public class DrainControl implements DrainMXBean {

	private volatile boolean drained;
	private volatile long drainedSinceMillis;

	private MBeanServer server;
	private ObjectName objectName;

	@Override
	public boolean isDrained() {
		return drained;
	}

	@Override
	public void setDrained(boolean drained) {
		boolean was = this.drained;
		this.drained = drained;
		this.drainedSinceMillis = drained ? System.currentTimeMillis() : 0L;
		// Audit trail: an operator just took this node out of (or back into)
		// rotation — that belongs in the log even at default levels.
		if (was != drained && SettingsManager.getSipLogger() != null) {
			SettingsManager.getSipLogger().warning(drained
					? "Options - node DRAINED by operator: OPTIONS now answers 503 Draining;"
							+ " the load balancer will stop offering new calls"
					: "Options - node RESUMED by operator: OPTIONS answers 200 again;"
							+ " the load balancer will restore this node on its next ping");
		}
	}

	@Override
	public long getDrainedSinceMillis() {
		return drainedSinceMillis;
	}

	/// Register this node's drain MBean, replacing any stale instance left by a
	/// prior deployment of the same app. Never fatal to the caller — the app
	/// must deploy (and answer pings) even if drain control is unavailable.
	///
	/// @param app the flattened context name, matching the Configuration
	///            MBean's `Name=` key
	public void register(String app) throws Exception {
		server = ManagementFactory.getPlatformMBeanServer();

		String cluster = clusterName(server);
		objectName = new ObjectName(cluster != null
				? "vorpal.blade:Name=" + app + ",Type=Drain,Cluster=" + cluster
				: "vorpal.blade:Name=" + app + ",Type=Drain");

		if (server.isRegistered(objectName)) {
			server.unregisterMBean(objectName);
		}

		StandardMBean mxbean = new StandardMBean(this, DrainMXBean.class, true);
		objectName = server.registerMBean(mxbean, objectName).getObjectName();
	}

	public void unregister() {
		if (server != null && objectName != null) {
			try {
				if (server.isRegistered(objectName)) {
					server.unregisterMBean(objectName);
				}
			} catch (Exception e) {
				// already gone; nothing to do
			} finally {
				objectName = null;
			}
		}
	}

	/// The ObjectName this control registered under, or null when unregistered.
	ObjectName getObjectName() {
		return objectName;
	}

	/// The cluster this node belongs to, or null on a non-clustered server.
	/// Same discovery the SettingsManager and ProxyBalancerServlet do — the
	/// ObjectNames must agree on the Cluster key.
	private static String clusterName(MBeanServer server) {
		try {
			String serverName = System.getProperty("weblogic.Name");
			ObjectName managedServer = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
			ObjectName cluster = (ObjectName) server.getAttribute(managedServer, "Cluster");
			return cluster != null ? (String) server.getAttribute(cluster, "Name") : null;
		} catch (Exception e) {
			return null;
		}
	}

}
