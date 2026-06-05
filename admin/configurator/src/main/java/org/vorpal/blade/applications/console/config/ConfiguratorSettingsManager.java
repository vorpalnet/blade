package org.vorpal.blade.applications.console.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.sip.ServletParseException;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// SettingsManager for the Configurator that owns the auto-publish
/// [ConfigurationMonitor] lifecycle.
///
/// The monitor watches `./config/custom/vorpal/*.json` and republishes
/// on-disk edits to live services via JMX — the same behavior the standalone
/// `watcher` WAR provides.
///
/// The framework calls [#initialize] on every config reload (see
/// [SettingsManager#initialize] and `Settings#reload`), including the
/// initial load during construction. Starting and stopping the monitor
/// from that hook makes the [ConfiguratorSettings#isAutoPublish] flag take
/// effect the moment it is flipped through the UI — no redeploy required.
public class ConfiguratorSettingsManager extends SettingsManager<ConfiguratorSettings> {

	private static final Logger logger = Logger.getLogger(ConfiguratorSettingsManager.class.getName());
	private static final Path WATCH_DIR = Paths.get("./config/custom/vorpal/");

	/// Defaults to null. Assigned by [#startMonitor] and cleared by
	/// [#stopMonitor]. Deliberately NOT a field initializer: [#initialize]
	/// runs inside the super constructor — before subclass field initializers
	/// — and null is the correct value at that point.
	private ConfigurationMonitor monitor;

	public ConfiguratorSettingsManager(ServletContextEvent event) throws Exception {
		super(event, ConfiguratorSettings.class, new ConfiguratorSettingsSample());
	}

	/// Framework hook, invoked on every `reload()`. Brings the monitor thread
	/// in line with the current `autoPublish` flag. A null config means
	/// auto-publish stays off — same as the shipped default.
	@Override
	public synchronized void initialize(ConfiguratorSettings config) throws ServletParseException {
		boolean autoPublish = (config != null) && config.isAutoPublish();
		if (autoPublish) {
			startMonitor();
		} else {
			stopMonitor();
		}
	}

	private synchronized void startMonitor() {
		if (monitor != null) {
			return; // already running
		}
		try {
			ConfigurationMonitor m = new ConfigurationMonitor();
			m.initialize(WATCH_DIR, true);
			m.setDaemon(true);
			m.setName("BLADE-configurator-autopublish");
			m.start();
			monitor = m;
			logger.info("configurator auto-publish ON, watching " + WATCH_DIR.toAbsolutePath());
		} catch (Exception e) {
			logger.log(Level.SEVERE, "configurator auto-publish failed to start", e);
		}
	}

	private synchronized void stopMonitor() {
		if (monitor == null) {
			return; // already stopped
		}
		try {
			monitor.shutdown();
			logger.info("configurator auto-publish OFF; use Save + Publish to apply changes");
		} catch (Exception e) {
			logger.log(Level.WARNING, "configurator auto-publish shutdown error", e);
		} finally {
			monitor = null;
		}
	}

	/// Stop the monitor on undeploy.
	public synchronized void shutdown() {
		stopMonitor();
	}
}
