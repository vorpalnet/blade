package org.vorpal.blade.applications.console.tuning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Tuning admin app's SettingsManager at startup so its
/// `name` / `tagline` / `description` metadata appear in JMX for the
/// Admin Portal deck and the Configurator form editor. See memory `[[admin-apps-use-settingsmanager]]`.
@WebListener
public class TuningSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(TuningSettingsStartup.class.getName());

	/// Where SettingsManager keeps per-app config (relative to the server's
	/// working dir / DOMAIN_HOME) — must match SettingsManager.CONFIG_BASE_PATH.
	private static final String CONFIG_BASE_PATH = "config/custom/vorpal";

	private SettingsManager<TuningSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, TuningSettings.class, new TuningSettingsSample());
			logger.info("tuning SettingsManager registered");
			materializeDomainConfigIfMissing(sce);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "tuning SettingsManager failed to register", e);
		}
	}

	/// On a fresh install no domain config file exists, so the app runs on the
	/// in-memory sample and edits have nowhere to persist. Write the sample to
	/// the domain config (`config/custom/vorpal/blade-tuning.json`) once, so the
	/// JVM-profiles editor has a real file to read and save into. Idempotent —
	/// skips if the file already exists, so it never clobbers operator edits.
	private void materializeDomainConfigIfMissing(ServletContextEvent sce) {
		try {
			String name = SettingsManager.deriveName(sce.getServletContext()); // blade-tuning
			Path file = Paths.get(CONFIG_BASE_PATH, name + ".json");
			if (Files.exists(file)) {
				return;
			}
			Files.createDirectories(file.getParent());
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new TuningSettingsSample());
			logger.info("Materialized domain config from sample: " + file.toAbsolutePath());
		} catch (Exception e) {
			logger.log(Level.WARNING, "Could not materialize tuning domain config from sample", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "tuning SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
