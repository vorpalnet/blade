package org.vorpal.blade.services.options;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// [SipQueuesMXBean], registered as `vorpal.blade:Name=<app>,Type=SipQueues`
/// (with `Cluster=` on a clustered server), beside the Drain MBean.
final class SipQueues implements SipQueuesMXBean {

	private MBeanServer server;
	private ObjectName objectName;

	@Override
	public int getTransportDepth() {
		return QueuePressure.transport().depth();
	}

	@Override
	public int getTransportCapacity() {
		return QueuePressure.transport().capacity();
	}

	@Override
	public int getTimerDepth() {
		return QueuePressure.timer().depth();
	}

	@Override
	public int getTimerCapacity() {
		return QueuePressure.timer().capacity();
	}

	@Override
	public int getBusyPercent() {
		SettingsManager<OptionsSettings> manager = OptionsSipServlet.settingsManager;
		OptionsSettings settings = (manager != null) ? manager.getCurrent() : null;
		Integer percent = (settings != null) ? settings.getQueuePressurePercent() : null;
		return Math.max(0, percent != null ? percent : QueuePressure.DEFAULT_PERCENT);
	}

	@Override
	public boolean isRefusing() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null && m.isRefusing();
	}

	@Override
	public long getRefusingSince() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getRefusingSince() : 0;
	}

	@Override
	public int getLatencyMillis() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getLatencyMillis() : -1;
	}

	@Override
	public int getRefuseMillis() {
		return overload().getRefuseMillis();
	}

	@Override
	public String getProtection() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getProtection() : "off";
	}

	@Override
	public int getGcPercent() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getGcPercent() : -1;
	}

	@Override
	public int getHeapAfterGcPercent() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getHeapAfterGcPercent() : -1;
	}

	@Override
	public long getLockFailuresPerMinute() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getLockFailuresPerMinute() : -1;
	}

	@Override
	public String[] getWarnings() {
		OverloadMonitor m = OptionsSipServlet.overloadMonitor;
		return m != null ? m.getWarnings().toArray(new String[0]) : new String[0];
	}

	private static OverloadSettings overload() {
		SettingsManager<OptionsSettings> manager = OptionsSipServlet.settingsManager;
		OptionsSettings settings = (manager != null) ? manager.getCurrent() : null;
		OverloadSettings o = (settings != null) ? settings.getOverload() : null;
		return o != null ? o : new OverloadSettings();
	}

	void register(String app) throws Exception {
		server = ManagementFactory.getPlatformMBeanServer();
		String cluster = DrainControl.clusterName(server);
		objectName = new ObjectName(cluster != null
				? "vorpal.blade:Name=" + app + ",Type=SipQueues,Cluster=" + cluster
				: "vorpal.blade:Name=" + app + ",Type=SipQueues");
		if (server.isRegistered(objectName)) {
			server.unregisterMBean(objectName);
		}
		objectName = server.registerMBean(new StandardMBean(this, SipQueuesMXBean.class, true), objectName).getObjectName();
	}

	void unregister() {
		try {
			if (server != null && objectName != null && server.isRegistered(objectName)) {
				server.unregisterMBean(objectName);
			}
		} catch (Exception e) {
			// already gone
		} finally {
			objectName = null;
		}
	}
}
