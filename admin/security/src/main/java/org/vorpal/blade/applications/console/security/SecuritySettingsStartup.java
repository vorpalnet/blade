package org.vorpal.blade.applications.console.security;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.security.JwtAuthConfig;
import org.vorpal.blade.framework.v3.security.JwtAuthFilter;

/// Registers the Security admin app's SettingsManager at startup (so its
/// metadata appears on the Admin Portal deck and its config is editable in the
/// Configurator), and publishes a live [JwtAuthConfig] supplier into the
/// ServletContext so [JwtAuthFilter] can read the current JWT settings on every
/// request.
///
/// The supplier — not a config snapshot — is what's published, so Configurator
/// edits take effect without a redeploy: the filter rebuilds its validator only
/// when the underlying config instance changes.
@WebListener
public class SecuritySettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(SecuritySettingsStartup.class.getName());

	private SettingsManager<SecuritySettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, SecuritySettings.class, new SecuritySettingsSample());

			Supplier<JwtAuthConfig> jwtConfigSupplier = () -> {
				SecuritySettings current = settingsManager.getCurrent();
				return (current == null) ? null : current.getJwt();
			};
			sce.getServletContext().setAttribute(JwtAuthFilter.CONFIG_SUPPLIER_ATTR, jwtConfigSupplier);

			logger.info("security SettingsManager registered; JWT config supplier published");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "security SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		sce.getServletContext().removeAttribute(JwtAuthFilter.CONFIG_SUPPLIER_ATTR);
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "security SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
