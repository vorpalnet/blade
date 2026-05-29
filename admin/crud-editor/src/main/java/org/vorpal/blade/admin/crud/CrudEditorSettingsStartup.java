package org.vorpal.blade.admin.crud;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the CRUD Editor's SettingsManager at startup so its `name` / `tagline` / `description` metadata
/// (and any future per-app settings) appear in JMX for the Admin Portal
/// deck and the Configurator form editor. See memory
/// `[[admin-apps-use-settingsmanager]]`.
@WebListener
public class CrudEditorSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(CrudEditorSettingsStartup.class.getName());

	private SettingsManager<CrudEditorSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, CrudEditorSettings.class, new CrudEditorSettingsSample());
			logger.info("crud-editor SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "crud-editor SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "crud-editor SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
