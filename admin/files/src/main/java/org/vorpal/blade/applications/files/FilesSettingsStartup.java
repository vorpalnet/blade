package org.vorpal.blade.applications.files;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Files admin app's SettingsManager at startup. Beyond the
/// `name` / `tagline` / `description` metadata the Admin Portal deck reads from
/// JMX, this is what loads the editable-file registry. The live
/// `SettingsManager` is stashed in the ServletContext so [FilesAPI] can read the
/// current registry without a singleton.
@WebListener
public class FilesSettingsStartup implements ServletContextListener {

	/// ServletContext attribute under which the live SettingsManager is stored.
	public static final String SETTINGS_ATTR = "org.vorpal.blade.files.settingsManager";

	private static final Logger logger = Logger.getLogger(FilesSettingsStartup.class.getName());

	private SettingsManager<FilesSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, FilesSettings.class, new FilesSettingsSample());
			sce.getServletContext().setAttribute(SETTINGS_ATTR, settingsManager);
			logger.info("files SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "files SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "files SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
