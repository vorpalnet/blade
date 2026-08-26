package org.vorpal.blade.test.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.v2.analytics.AnalyticsB2buaSample;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.configuration.SettingsManager;

/// Manages this application's config file. A v3 [SettingsManager] binds its
/// config type once (the `extends` clause) and asks the subclass for two
/// things: [#sample], the seed written on first deploy, and — optionally —
/// [#refreshed], run on every (re)load.
public class TestB2buaSettingsManager extends SettingsManager<TestB2buaConfiguration> {

	public TestB2buaSettingsManager(SipServletContextEvent event) throws ServletException, IOException {
		super(event);
	}

	/// The first-run seed written to a fresh config file on first deploy.
	/// Note: the color '{CLEARTEXT}Blue' is encrypted by the SettingsManager.
	@Override
	protected TestB2buaConfiguration sample() {
		TestB2buaConfiguration config = new TestB2buaConfiguration();

		LogParametersDefault logging = new LogParametersDefault();
		logging.setLoggingLevel(LoggingLevel.FINE);
		config.setLogging(logging);
		config.setSession(new SessionParametersDefault());
		config.setAnalytics(new AnalyticsB2buaSample());

		config.traveler = "Sir Lancelot of Camelot";
		config.quest = "To seek the Holy Grail";
		config.color = "{CLEARTEXT}Blue";

		return config;
	}

	/// Runs on the initial load and on every change pushed through the
	/// Configurator or JMX.
	@Override
	protected void refreshed(TestB2buaConfiguration config) throws ServletParseException {

		sipLogger.fine(
				"Stop. Who would cross the Bridge of Death must answer me these questions three, ere the other side he see.");
		sipLogger.info("What is your name? " + config.traveler);
		sipLogger.info("What is your quest? " + config.quest);
		sipLogger.info("What is your favorite color? " + Color.BLUE_BOLD_BRIGHT(config.color));

	}

}
