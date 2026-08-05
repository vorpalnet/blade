package org.vorpal.blade.services.webrtc;

import java.util.Enumeration;
import java.util.Properties;

import java.util.Iterator;
import java.util.ServiceLoader;

import javax.media.mscontrol.spi.Driver;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v3.AsyncSipServlet;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.media.MediaCallflow;

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
/// cannot do is reach the PSTN, or record: both need a media server, the first because a phone
/// cannot speak ICE or DTLS-SRTP, the second because two browsers' media is encrypted to each other
/// and cannot be tapped.
@WebListener
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class WebrtcServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		try {
			Driver driver = findDriver();
			if (driver == null) {
				sipLogger.info("WebrtcServlet: no JSR-309 driver registered — "
						+ "browser-to-browser calls will work; PSTN and recording will not");
				return;
			}
			MediaCallflow.setMsControlFactory(driver.getFactory(mediaProperties(event)));
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

	/// The installed JSR-309 driver, or null if none is on the classpath.
	///
	/// Deliberately **not** `javax.media.mscontrol.spi.DriverManager`. That class dates from 2009 and
	/// discovers drivers through `sun.misc.Service`, which was removed in Java 9 — so merely touching
	/// `DriverManager` on a modern JVM throws `ClassNotFoundException: sun.misc.Service` and, from a
	/// servlet with `loadOnStartup`, fails the whole deployment. `ServiceLoader` reads exactly the
	/// same `META-INF/services/javax.media.mscontrol.spi.Driver` entries, so discovery is unchanged.
	private static Driver findDriver() {
		Iterator<Driver> drivers = ServiceLoader.load(Driver.class, WebrtcServlet.class.getClassLoader())
				.iterator();
		return drivers.hasNext() ? drivers.next() : null;
	}

	/// Driver settings, read from servlet context parameters so a deployment can point the gateway at
	/// its media server, STUN and TURN without a rebuild. Keys pass through verbatim; each driver
	/// documents the ones it understands (see the JSR-309 media controller driver's documentation).
	private static Properties mediaProperties(SipServletContextEvent event) {
		Properties properties = new Properties();
		Enumeration<String> names = event.getServletContext().getInitParameterNames();
		while (names.hasMoreElements()) {
			String name = names.nextElement();
			properties.setProperty(name, event.getServletContext().getInitParameter(name));
		}
		return properties;
	}
}
