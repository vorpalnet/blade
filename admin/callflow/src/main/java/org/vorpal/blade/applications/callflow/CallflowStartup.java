package org.vorpal.blade.applications.callflow;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Trace app's SettingsManager at startup so the app's
/// `name` / `tagline` / `description` appear on the Admin Portal launcher deck
/// (read over JMX from `SettingsMXBean.getCurrentJson()`).
@WebListener
public class CallflowStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(CallflowStartup.class.getName());

	private static SettingsManager<CallflowSettings> settingsManager;

	/// The live settings, or null before startup completes. [CallflowsAPI]
	/// reads `sourceServer` from here on every request, so a Configurator save
	/// takes effect without a redeploy.
	static CallflowSettings settings() {
		SettingsManager<CallflowSettings> sm = settingsManager;
		return sm != null ? sm.getCurrent() : null;
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, CallflowSettings.class, new CallflowSettingsSample());
			logger.info("callflow SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "callflow SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "callflow SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
