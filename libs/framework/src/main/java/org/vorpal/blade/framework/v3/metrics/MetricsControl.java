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
	public String getFrameworkVersion() {
		return FRAMEWORK_VERSION;
	}

	/// Read once: the manifest of the JAR this class came from.
	static final String FRAMEWORK_VERSION = frameworkVersion();

	private static String frameworkVersion() {
		try {
			java.net.URL self = MetricsControl.class.getResource("MetricsControl.class");
			String url = String.valueOf(self);
			int bang = url.lastIndexOf("!/");
			if (bang < 0) {
				return "unknown (not from a JAR)";
			}
			// The loader's own URL for this JAR's manifest: a class inside a JAR
			// inside a WAR has a nested URL that only its loader opens.
			String jar = url.substring(0, bang + 2);
			java.net.URL manifest = null;
			for (java.util.Enumeration<java.net.URL> e = MetricsControl.class.getClassLoader()
					.getResources("META-INF/MANIFEST.MF"); e.hasMoreElements();) {
				java.net.URL candidate = e.nextElement();
				if (String.valueOf(candidate).startsWith(jar)) {
					manifest = candidate;
					break;
				}
			}
			if (manifest == null) {
				return "unknown (no manifest beside " + jar + ")";
			}
			try (java.io.InputStream in = manifest.openStream()) {
				java.util.jar.Attributes a = new java.util.jar.Manifest(in).getMainAttributes();
				String version = a.getValue("Implementation-Version");
				String mode = a.getValue("Blade-Build-Mode");
				String built = a.getValue("Implementation-Build-Date");
				return (version != null ? version : "unversioned") + (mode != null ? " " + mode : "")
						+ (built != null ? ", built " + built : "");
			}
		} catch (Exception e) {
			return "unknown (" + e.getClass().getSimpleName() + ")";
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
