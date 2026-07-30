package org.vorpal.blade.framework.v3.metrics;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import com.fasterxml.jackson.databind.ObjectMapper;

/// [MetricsMXBean] implementation backed by one app's [MetricsRegistry] on one
/// node.
///
/// Registered through an explicit [StandardMBean] wrapper with `isMXBean=true`,
/// for the same reason `TesterControl` does it: WLS 14.1.1's JMX
/// auto-introspection silently drops no-arg getters from the computed
/// MBeanInfo otherwise, and the report would be invisible.
///
/// The ObjectName deliberately mirrors the app's Configuration MBean — same
/// `Name=`, same `Cluster=` key — so the two join on a value a console already
/// has.
public class MetricsControl implements MetricsMXBean {

	private final MetricsRegistry registry;
	private final String app;
	private final String node;
	private final String cluster;
	private final ObjectMapper mapper = new ObjectMapper();

	private MBeanServer server;
	private ObjectName objectName;

	/// @param registry the app's registry
	/// @param app      the flattened context name, matching the Configuration
	///                 MBean's `Name=` key
	/// @param cluster  the cluster name, or null on a standalone server
	public MetricsControl(MetricsRegistry registry, String app, String cluster) {
		this.registry = registry;
		this.app = app;
		this.cluster = cluster;
		this.node = System.getProperty("weblogic.Name");
	}

	/// Register this node's metrics MBean, replacing any stale instance left by
	/// a prior deployment of the same app.
	public void register() throws Exception {
		server = ManagementFactory.getPlatformMBeanServer();

		if (cluster != null) {
			objectName = new ObjectName("vorpal.blade:Name=" + app + ",Type=Metrics,Cluster=" + cluster);
		} else {
			objectName = new ObjectName("vorpal.blade:Name=" + app + ",Type=Metrics");
		}

		if (server.isRegistered(objectName)) {
			server.unregisterMBean(objectName);
		}

		StandardMBean mxbean = new StandardMBean(this, MetricsMXBean.class, true);
		objectName = server.registerMBean(mxbean, objectName).getObjectName();
	}

	public void unregister() throws Exception {
		if (server != null && objectName != null && server.isRegistered(objectName)) {
			server.unregisterMBean(objectName);
		}
	}

	@Override
	public String getReportJson() {
		try {
			return mapper.writeValueAsString(registry.report(app, node, cluster));
		} catch (Exception e) {
			return errorJson(e);
		}
	}

	@Override
	public int getMetricCount() {
		return registry.size();
	}

	@Override
	public void reset() {
		registry.reset();
	}

	private String errorJson(Exception e) {
		try {
			return mapper.writeValueAsString(java.util.Collections.singletonMap("error", String.valueOf(e)));
		} catch (Exception nested) {
			return "{\"error\":\"metrics report unavailable\"}";
		}
	}
}
