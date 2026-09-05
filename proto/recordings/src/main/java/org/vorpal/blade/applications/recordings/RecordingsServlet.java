package org.vorpal.blade.applications.recordings;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Loads the app's settings, and nothing else.
///
/// A plain servlet listener rather than a SIP servlet: this app never touches a
/// call. It reads recordings that a call already produced, which is why it can
/// live on the admin tier away from the engines.
@WebListener
public class RecordingsServlet implements ServletContextListener {

	private static volatile SettingsManager<RecordingsSettings> settings;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		try {
			settings = new SettingsManager<>(event, RecordingsSettings.class, new RecordingsSettingsSample());
		} catch (Exception e) {
			// Do not fail the deployment. With no settings the evaluator sees a
			// null policy and refuses everything, which is the right posture for
			// an app whose configuration did not load, and far easier to diagnose
			// from a running server that says so than from a WAR that would not
			// deploy.
			java.util.logging.Logger.getLogger(RecordingsServlet.class.getName())
					.log(java.util.logging.Level.SEVERE,
							"recordings: settings did not load; every request will be refused", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		try {
			if (settings != null) {
				settings.unregister();
			}
		} catch (Exception ignore) {
			// best effort on the way down
		}
		settings = null;
	}

	/// The live settings, or null before startup finished.
	///
	/// Null matters: [AccessEvaluator] treats a null policy as "deny everything"
	/// and says so, which is the right answer for a request that arrives before
	/// the rules are loaded. Defaulting to an empty-but-present policy would look
	/// the same and mean something different.
	static RecordingsSettings settings() {
		return (settings == null) ? null : settings.getCurrent();
	}
}
