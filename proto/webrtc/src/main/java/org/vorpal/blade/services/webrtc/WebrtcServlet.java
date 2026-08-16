package org.vorpal.blade.services.webrtc;

import java.util.Properties;
import java.util.ServiceLoader;

import javax.media.mscontrol.spi.Driver;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.AsyncSipServlet;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.security.JwtAuthConfig;

/// The SIP half of the WebRTC gateway.
///
/// One WAR holds both this and [SignalEndpoint]; WebLogic runs the SIP and WebSocket containers over
/// a shared classloader, so a call arriving on a SIP trunk and a browser arriving on `wss://` meet
/// in the same application with no bridge between them.
///
/// ## Media is optional
///
/// The JSR-309 driver is resolved the same way `proto/player` resolves it — from the registered
/// driver, with its properties passed through verbatim — and **failing to find one is not fatal**.
/// Browser-to-browser calls are relayed peer-to-peer and never touch a media server, so a
/// deployment with no media plane at all still places and receives calls between browsers. What it
/// cannot do is reach a phone, because a phone cannot speak ICE or DTLS-SRTP and something has to
/// terminate one side and speak the other. Nor can any downstream service — recording,
/// transcription, conferencing — get at a relayed call's audio, which is encrypted directly between
/// the two browsers; anchoring is what makes media available to anything at all.
@WebListener
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class WebrtcServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	/// Read by [SignalEndpoint] on every `session.connect`. Static because the
	/// WebSocket container builds its own endpoint instances and hands them no
	/// reference to the SIP servlet; this is the seam between the two halves of
	/// the converged application.
	public static SettingsManager<WebrtcSettings> settings;

	/// This engine's SIP interface, captured at startup from the standard
	/// `javax.servlet.sip.outboundInterfaces` context attribute. It is the host
	/// and port [BrowserRegistration] writes into every contact — the address
	/// that makes a registered browser's contact routable back to this node.
	private static volatile javax.servlet.sip.SipURI outboundInterface;

	public static javax.servlet.sip.SipURI outboundInterface() {
		return outboundInterface;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		captureOutboundInterface(event);
		try {
			settings = new SettingsManager<>(event, WebrtcSettings.class, new WebrtcSettingsSample());
			JwtAuthConfig jwt = jwtConfig();
			if (jwt == null || !jwt.isEnabled()) {
				sipLogger.severe("WebrtcServlet: browser authentication is DISABLED — "
						+ "any client that can reach this node may claim any address and place calls. "
						+ "Set jwt.enabled and jwt.jwksUri in webrtc.json to close it.");
			} else {
				sipLogger.info("WebrtcServlet: browser authentication enabled; issuer=" + jwt.getIssuer()
						+ ", jwks=" + jwt.getJwksUri());
			}
		} catch (Exception e) {
			// Without settings the authenticator sees a null config, which is the
			// open path — so this cannot be allowed to pass quietly.
			sipLogger.severe("WebrtcServlet: could not load settings, so browser authentication "
					+ "is NOT in force: " + e.getMessage());
		}

		try {
			WebrtcSettings current = (settings == null) ? null : settings.getCurrent();
			String wanted = (current == null) ? null : current.getDriverName();
			Driver driver = findDriver(wanted);
			if (driver == null) {
				if (wanted != null && !wanted.isEmpty()) {
					// Asked for a specific driver and it is not installed. Still not fatal — the
					// relayed path needs no media plane — but it is a configuration mistake rather
					// than a deployment without media, and the two deserve different volumes.
					sipLogger.severe("WebrtcServlet: no JSR-309 driver named '" + wanted + "' is registered — "
							+ "check driverName in webrtc.json; calls to phones will not work");
				} else {
					sipLogger.info("WebrtcServlet: no JSR-309 driver registered — "
							+ "browser-to-browser calls will work; calls to phones will not");
				}
				return;
			}
			MediaCallflow.setMsControlFactory(driver.getFactory(mediaProperties(current)));
			sipLogger.info("WebrtcServlet: media driver '" + driver.getName() + "' installed");
		} catch (Exception e) {
			// Don't fail deployment: the relayed path needs nothing from the media plane.
			sipLogger.severe("WebrtcServlet: could not obtain a JSR-309 factory: " + e.getMessage());
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		sipLogger.info("WebrtcServlet: stopped");
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) {
		// In-dialog requests reach the callflow's own continuations through the framework; only a
		// new call needs one chosen.
		if ("INVITE".equals(request.getMethod()) && request.isInitial()) {
			return new InboundToBrowser();
		}
		return null;
	}

	/// The live browser-authentication settings, or null when the app has none —
	/// which [BrowserAuthenticator] treats as "refuse", not as "allow".
	public static JwtAuthConfig jwtConfig() {
		SettingsManager<WebrtcSettings> sm = settings;
		WebrtcSettings current = (sm == null) ? null : sm.getCurrent();
		return (current == null) ? null : current.getJwt();
	}

	/// The configured media policy, AUTO when settings are not loaded.
	public static MediaMode mediaMode() {
		SettingsManager<WebrtcSettings> sm = settings;
		WebrtcSettings current = (sm == null) ? null : sm.getCurrent();
		return (current == null || current.getMediaMode() == null) ? MediaMode.AUTO : current.getMediaMode();
	}

	/// Expires for the REGISTER sent on a browser's behalf. The setter clamps,
	/// so the only case handled here is settings not being loaded at all.
	public static int registerExpiresSeconds() {
		SettingsManager<WebrtcSettings> sm = settings;
		WebrtcSettings current = (sm == null) ? null : sm.getCurrent();
		return (current == null || current.getRegisterExpiresSeconds() == null)
				? 3600 : current.getRegisterExpiresSeconds();
	}

	/// Pick the interface browsers' contacts will name. First TCP interface,
	/// else the first of any transport — TCP because the fork that comes back
	/// through it carries a full SDP body, which UDP would fragment.
	@SuppressWarnings("unchecked")
	private void captureOutboundInterface(SipServletContextEvent event) {
		Object attribute = event.getServletContext().getAttribute("javax.servlet.sip.outboundInterfaces");
		if (!(attribute instanceof java.util.List) || ((java.util.List<?>) attribute).isEmpty()) {
			sipLogger.severe("WebrtcServlet: no javax.servlet.sip.outboundInterfaces — "
					+ "browser registrations cannot build a routable contact");
			return;
		}
		java.util.List<javax.servlet.sip.SipURI> interfaces = (java.util.List<javax.servlet.sip.SipURI>) attribute;
		javax.servlet.sip.SipURI chosen = interfaces.get(0);
		for (javax.servlet.sip.SipURI candidate : interfaces) {
			if ("tcp".equalsIgnoreCase(candidate.getTransportParam())) {
				chosen = candidate;
				break;
			}
		}
		outboundInterface = chosen;
		sipLogger.info("WebrtcServlet: contact interface " + chosen);
	}

	/// The installed JSR-309 driver, or null if none is on the classpath.
	///
	/// Deliberately **not** `javax.media.mscontrol.spi.DriverManager`. That class dates from 2009 and
	/// discovers drivers through `sun.misc.Service`, which was removed in Java 9 — so merely touching
	/// `DriverManager` on a modern JVM throws `ClassNotFoundException: sun.misc.Service` and, from a
	/// servlet with `loadOnStartup`, fails the whole deployment. `ServiceLoader` reads exactly the
	/// same `META-INF/services/javax.media.mscontrol.spi.Driver` entries, so discovery is unchanged.
	/// @param wanted the configured `driverName`, or null/blank to take whichever driver is
	///               registered — the usual case, since a deployment installs one
	private static Driver findDriver(String wanted) {
		Driver first = null;
		for (Driver driver : ServiceLoader.load(Driver.class, WebrtcServlet.class.getClassLoader())) {
			if (wanted != null && !wanted.isEmpty()) {
				if (wanted.equals(driver.getName())) {
					return driver;
				}
			} else if (first == null) {
				first = driver;
			}
		}
		return first;
	}

	/// Driver settings from `webrtc.json`, handed to the driver verbatim.
	///
	/// These used to be harvested from servlet context init-parameters, which meant the one part of
	/// this application an operator most needs to change — where the media server is — was the one
	/// part not in its configuration file. It also swept in every unrelated context parameter the
	/// container happened to expose. No BLADE application ships a `<context-param>`, so nothing was
	/// configured this way in practice; `proto/player` already reads the same settings from its own
	/// configuration, and this matches it.
	private static Properties mediaProperties(WebrtcSettings current) {
		Properties properties = new Properties();
		if (current != null && current.getDriverProperties() != null) {
			properties.putAll(current.getDriverProperties());
		}
		return properties;
	}
}
