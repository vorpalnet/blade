package org.vorpal.blade.services.player;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlFactory;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.media.mscontrol.spi.Driver;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.AsyncSipServlet;
import org.vorpal.blade.framework.v3.media.MediaCallflow;

/// A **vendor-neutral JSR-309** player/recorder service: it answers an inbound call, anchors the
/// call's media on a 309 media server, plays a prompt/music, and optionally records the caller. In
/// conference mode ([PlayerSettings#isConference]) callers to the same dialed user share one
/// [javax.media.mscontrol.mixer.MediaMixer] instead — see [Room].
///
/// It speaks only `javax.media.mscontrol.*`. At startup it obtains the [MsControlFactory] from a
/// registered 309 [Driver] (any JSR-309 media controller driver works)
/// and installs it on [MediaCallflow]. Nothing here knows about the media server — that lives
/// entirely behind the driver.
@WebListener
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class PlayerServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<PlayerSettings> settings;

	/// Node-local registry of the live media anchor per call (keyed by app-session id). Live 309
	/// objects aren't serializable, so this is intentionally node-local; on failover the anchor is
	/// rebuilt, not migrated (matches MediaCallflow's reattach TODO).
	public static final Map<String, Anchor> LIVE = new ConcurrentHashMap<>();

	/// The live media objects for one call — the session (owns the pipeline) and its media group; or,
	/// in conference mode, the caller's leg and the room it is in (the room owns the session).
	public static final class Anchor {
		public final MediaSession ms;
		public volatile MediaGroup mg;
		public volatile NetworkConnection nc;
		public volatile String room;

		public Anchor(MediaSession ms) {
			this.ms = ms;
		}
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settings = new SettingsManager<>(event, PlayerSettings.class, new PlayerSettingsSample());
		try {
			MediaCallflow.setMsControlFactory(obtainFactory(settings.getCurrent()));
			sipLogger.info("PlayerServlet: JSR-309 MsControlFactory installed");
		} catch (Exception e) {
			// Don't fail deployment — log and let the first call surface the misconfig. The factory
			// does not connect to the media server here (connection is per-session, lazy).
			sipLogger.severe("PlayerServlet: could not obtain a JSR-309 MsControlFactory: " + e.getMessage());
		}
	}

	/// Resolve the 309 factory from the configured driver (or the sole registered driver), passing the
	/// driver-specific properties from config verbatim.
	private static MsControlFactory obtainFactory(PlayerSettings cfg) throws ServletException {
		Properties props = new Properties();
		if (cfg.getDriverProperties() != null) {
			props.putAll(cfg.getDriverProperties());
		}
		try {
			// Discovery goes through ServiceLoader rather than
			// javax.media.mscontrol.spi.DriverManager. That class dates from 2009 and finds drivers
			// via sun.misc.Service, which Java 9 removed — so on a modern JVM merely touching it
			// throws ClassNotFoundException: sun.misc.Service, and from a loadOnStartup servlet that
			// fails the whole deployment. ServiceLoader reads the same
			// META-INF/services/javax.media.mscontrol.spi.Driver entries, so nothing else changes.
			String name = cfg.getDriverName();
			Driver fallback = null;
			for (Driver driver : ServiceLoader.load(Driver.class, PlayerServlet.class.getClassLoader())) {
				if (name != null && !name.isEmpty()) {
					if (name.equals(driver.getName())) {
						return driver.getFactory(props);
					}
				} else if (fallback == null) {
					fallback = driver;
				}
			}
			if (name != null && !name.isEmpty()) {
				throw new ServletException("no JSR-309 driver named '" + name + "' is registered");
			}
			if (fallback == null) {
				throw new ServletException("no JSR-309 driver is registered");
			}
			return fallback.getFactory(props);
		} catch (ServletException e) {
			throw e;
		} catch (Exception e) {
			throw new ServletException("getFactory failed", e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			// Best-effort teardown of any media still anchored on this node.
			for (Anchor a : LIVE.values()) {
				try {
					if (a.room == null) {
						a.ms.release(); // a room's session is released with the room below
					}
				} catch (Exception ignore) {
					// best effort
				}
			}
			LIVE.clear();
			Room.closeAll();
			// A driver may own threads (node probes); an undeploy must stop them or the old
			// classloader lives on. Vendor-neutral: only the standard AutoCloseable is assumed.
			MsControlFactory factory = MediaCallflow.getMsControlFactory();
			if (factory instanceof AutoCloseable) {
				try {
					((AutoCloseable) factory).close();
				} catch (Exception ignore) {
					// best effort
				}
			}
			MediaCallflow.setMsControlFactory(null);
			if (settings != null) {
				settings.unregister();
			}
		} catch (Exception e) {
			sipLogger.severe("PlayerServlet.servletDestroyed: " + e.getMessage());
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		switch (request.getMethod()) {
		case "INVITE":
			return request.isInitial() ? new PlayerCallflow(settings.getCurrent()) : null;
		case "BYE":
		case "CANCEL":
			return new PlayerBye();
		case "INFO":
			return new PlayerInfo(); // DTMF-over-INFO into an active prompt() collect
		default:
			return null; // ACK and everything else: nothing to do
		}
	}
}
