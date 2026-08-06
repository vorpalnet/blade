package org.vorpal.blade.applications.phone;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.security.JwtAuthException;
import org.vorpal.blade.framework.v3.security.JwtIssuer;

/// Registers the phone's SettingsManager and builds its token issuer at startup.
///
/// The SettingsManager gives the app its `vorpal.blade:Name=blade-phone,
/// Type=Configuration` MBean, which is what makes it editable in the
/// Configurator and gives it a card on the Portal deck. The settings file name
/// derives from the deployment name (`blade-phone.war`), so config lives at
/// `./config/custom/vorpal/blade-phone.json` — not `phone.json`.
///
/// The [JwtIssuer] is built once here rather than per request because
/// constructing one generates an RSA keypair. It is deliberately **not** rebuilt
/// when config changes: the key identifies this process, not this configuration,
/// and re-generating it would invalidate the JWKS the engine tier has cached
/// while every browser was still using it. The settings that describe the
/// *token* — issuer, audience, TTL — are read at mint time, so those do take
/// effect on a config save.
@WebListener
public class PhoneStartupListener implements ServletContextListener {

	private static final Logger log = Logger.getLogger(PhoneStartupListener.class.getName());

	/// Shared static accessors, the same shape the API Explorer and portal use.
	/// The JAX-RS resources read both.
	public static SettingsManager<PhoneSettings> settingsManager;
	public static JwtIssuer issuer;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<>(event, PhoneSettings.class, new PhoneSettingsSample());
			log.info("WebRTC Phone SettingsManager registered; config at ./config/custom/vorpal/blade-phone.json");
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to register the WebRTC Phone SettingsManager — "
					+ "the app will not appear in the Configurator and cannot mint tokens", e);
			return;
		}

		try {
			issuer = new JwtIssuer(current().getJwt());
			log.info("WebRTC Phone token issuer ready; kid=" + issuer.keyId()
					+ ", public keys at ./api/v1/jwks.json");
		} catch (JwtAuthException e) {
			log.log(Level.SEVERE, "Failed to build the WebRTC Phone token issuer — "
					+ "browsers will not be able to authenticate to the gateway", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		issuer = null;
		try {
			if (settingsManager != null) {
				settingsManager.unregister();
			}
		} catch (Exception e) {
			// best-effort on shutdown
		}
	}

	/// The live settings, or a default instance when the manager failed to start
	/// — so a resource can always ask a question and get an answer rather than
	/// having to null-check its own configuration.
	static PhoneSettings current() {
		SettingsManager<PhoneSettings> sm = settingsManager;
		PhoneSettings settings = (sm == null) ? null : sm.getCurrent();
		return (settings == null) ? new PhoneSettingsSample() : settings;
	}
}
