package org.vorpal.blade.framework.v3.tester;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.ObjectMapper;

/// [TesterMXBean] implementation backed by this node's [LoadEngine] and
/// [TesterMetrics]. Registered via an explicit [StandardMBean] wrapper with
/// `isMXBean=true` — same as `SettingsManager` — because WLS 14.1.1's JMX
/// auto-introspection silently drops no-arg getters from the computed
/// MBeanInfo otherwise.
public class TesterControl implements TesterMXBean {

	private final LoadEngine engine;
	private final TesterMetrics metrics;
	private final ObjectMapper mapper = new ObjectMapper();

	private MBeanServer server;
	private ObjectName objectName;

	public TesterControl(LoadEngine engine, TesterMetrics metrics) {
		this.engine = engine;
		this.metrics = metrics;
	}

	/// Registers as `vorpal.blade:Name=<name>,Type=TesterControl` (plus a
	/// `Cluster` key on managed servers), unregistering any stale instance
	/// from a prior deployment first.
	public void register(String name) throws Exception {
		server = ManagementFactory.getPlatformMBeanServer();

		String clusterName = SettingsManager.getClusterName();
		if (clusterName != null) {
			objectName = new ObjectName("vorpal.blade:Name=" + name + ",Type=TesterControl,Cluster=" + clusterName);
		} else {
			objectName = new ObjectName("vorpal.blade:Name=" + name + ",Type=TesterControl");
		}

		if (server.isRegistered(objectName)) {
			server.unregisterMBean(objectName);
		}

		StandardMBean mxbean = new StandardMBean(this, TesterMXBean.class, true);
		objectName = server.registerMBean(mxbean, objectName).getObjectName();
	}

	public void unregister() throws Exception {
		if (server != null && objectName != null && server.isRegistered(objectName)) {
			server.unregisterMBean(objectName);
		}
	}

	@Override
	public String getStatusJson() {
		try {
			return mapper.writeValueAsString(engine.getStatus());
		} catch (Exception e) {
			return errorJson(e);
		}
	}

	@Override
	public String getReportJson() {
		try {
			return mapper.writeValueAsString(metrics.report());
		} catch (Exception e) {
			return errorJson(e);
		}
	}

	@Override
	public String startLoad(String loadRequestJson) {
		try {
			LoadRequest request = (loadRequestJson == null || loadRequestJson.trim().isEmpty()) ? new LoadRequest()
					: mapper.readValue(loadRequestJson, LoadRequest.class);
			engine.start(request);
			return getStatusJson();
		} catch (Exception e) {
			return errorJson(e);
		}
	}

	@Override
	public String stopLoad() {
		engine.stop();
		return getStatusJson();
	}

	@Override
	public void resetMetrics() {
		metrics.reset();
	}

	private String errorJson(Exception e) {
		try {
			return mapper.writeValueAsString(java.util.Collections.singletonMap("error", e.getMessage()));
		} catch (Exception ignore) {
			return "{\"error\":\"" + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}";
		}
	}
}
