package org.vorpal.blade.applications.dashboard;

import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;

/// Registers the dashboard's SettingsManager so it appears on the Admin Portal
/// deck and its configuration is Configurator-editable. The live settings are
/// stashed on the ServletContext so the data servlet reads current values
/// without a redeploy, beside the [AnalyticsStore] that owns the JPA factory.
@WebListener
public class DashboardSettingsStartup implements ServletContextListener {

	public static final String SETTINGS_ATTR = "org.vorpal.blade.dashboard.settings";

	/// The live settings, or null before they are registered.
	@SuppressWarnings("unchecked")
	static DashboardSettings settings(javax.servlet.ServletContext context) {
		Object sm = context.getAttribute(SETTINGS_ATTR);
		return (sm instanceof SettingsManager) ? ((SettingsManager<DashboardSettings>) sm).getCurrent() : null;
	}

	private static final Logger logger = Logger.getLogger(DashboardSettingsStartup.class.getName());

	private SettingsManager<DashboardSettings> settingsManager;
	private SubscriptionRegistrar ops;
	private ScheduledFuture<?> sampling;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, DashboardSettings.class, new DashboardSettingsSample());
			sce.getServletContext().setAttribute(SETTINGS_ATTR, settingsManager);
			logger.info("dashboard settings registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "dashboard settings failed to register", e);
		}
		sce.getServletContext().setAttribute(AnalyticsStore.ATTR, new AnalyticsStore());
		// After the settings, not in a listener of its own: the settings carry
		// any `events.providerUrl` override, and a separate listener could start
		// first and look in the wrong place (the container does not order
		// annotated listeners).
		ops = SubscriptionRegistrar.named(OpsFeed.SUBSCRIPTION).types(OpsFeed.TYPES).live()
				.start(sce.getServletContext(), OpsFeed.FEED);
		// The dashboard publishes facts of its own now (AppSampling), so it
		// announces itself like any application: every event's row resolves to
		// this instance by its start time, and without one each would mint a
		// new application row.
		Analytics.applicationStart();
		sampling = AppSampling.start();
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (sampling != null) {
			sampling.cancel(false);
			sampling = null;
		}
		Analytics.applicationStop();
		if (ops != null) {
			ops.stop();
		}
		Object store = sce.getServletContext().getAttribute(AnalyticsStore.ATTR);
		if (store instanceof AnalyticsStore) {
			((AnalyticsStore) store).close();
		}
		sce.getServletContext().removeAttribute(AnalyticsStore.ATTR);
		sce.getServletContext().removeAttribute(SETTINGS_ATTR);
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "dashboard settings unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
