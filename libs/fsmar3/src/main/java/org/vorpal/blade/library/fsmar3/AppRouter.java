package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.ar.SipApplicationRouter;
import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;
import javax.servlet.sip.ar.SipRouteModifier;
import javax.servlet.sip.ar.SipTargetedRequestInfo;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.bea.wcp.sip.engine.SipServletRequestAdapter;
import com.bea.wcp.sip.engine.server.SipApplicationSessionImpl;

/// FSMAR v3 Application Router implementation.
///
/// Evaluates a finite state machine to determine which SIP application
/// should handle each incoming request. Uses [RequestSelector][org.vorpal.blade.framework.v3.configuration.RequestSelector]
/// for pattern matching instead of v2 Conditions.
public class AppRouter implements SipApplicationRouter {

	protected static String FSMAR = "fsmar3";
	public static Logger sipLogger;
	private static SettingsManager<AppRouterConfiguration> settingsManager;
	protected static HashMap<String, String> deployed = new HashMap<>();

	@Override
	public void init() {
		try {
			settingsManager = new SettingsManager<>(FSMAR, AppRouterConfiguration.class,
					new AppRouterConfigurationSample());
			sipLogger = SettingsManager.getSipLogger();
		} catch (Exception e) {
			e.printStackTrace();
			sipLogger.severe(e);
		}
	}

	@Override
	public void init(Properties properties) {
		this.init();
	}

	@Override
	public SipApplicationRouterInfo getNextApplication(SipServletRequest request, SipApplicationRoutingRegion region,
			SipApplicationRoutingDirective directive, SipTargetedRequestInfo requestInfo, Serializable saved) {

		SipApplicationRouterInfo nextApp = null;

		try {
			AppRouterConfiguration config = (saved != null) ? (AppRouterConfiguration) saved
					: settingsManager.getCurrent();

			if (config == null) {
				throw new Exception("Invalid FSMAR configuration file.");
			}

			// Determine previous application
			SipApplicationSessionImpl sasi = ((SipServletRequestAdapter) request)
					.getImpl().getSipApplicationSessionImpl();
			String previous = (sasi != null) ? SettingsManager.basename(sasi.getApplicationName()) : "null";

			if (sipLogger.isLoggable(Level.FINE)) {
				String str = "getNextApplication... " + request.getMethod() + " " + request.getRequestURI();
				str += ", previous: " + previous;
				str += (region != null) ? ", region: " + region.getLabel() + " " + region.getType() : "";
				str += (directive != null) ? ", directive: " + directive : "";
				str += (requestInfo != null)
						? ", requestInfo: " + requestInfo.getType() + " " + requestInfo.getApplicationName()
						: "";
				sipLogger.fine(str);
			}

			// For targeted sessions, route directly
			if (requestInfo != null) {
				String deployedApp = getDeployedApp(requestInfo.getApplicationName());
				nextApp = new SipApplicationRouterInfo(deployedApp, region, null, null,
						SipRouteModifier.NO_ROUTE, config);
			}

			// URI-based direct routing (e.g. "sip:hold")
			if (nextApp == null) {
				SipURI sipUri = (SipURI) request.getRequestURI();
				if (null == sipUri.getUser()) {
					String app = getDeployedApp(sipUri.getHost());
					if (app != null) {
						nextApp = new SipApplicationRouterInfo(app, region, null, null,
								SipRouteModifier.NO_ROUTE, config);
					}
				}
			}

			// Evaluate the state machine
			if (nextApp == null) {
				State state = config.getState(previous);
				Trigger trigger = state.getTrigger(request.getMethod());
				if (trigger != null) {
					Transition matched = null;

					Iterator<Transition> itr = trigger.getTransitions().iterator();
					if (itr.hasNext()) {
						while (itr.hasNext()) {
							Transition t = itr.next();
							if (t.matches(request)) {
								matched = t;
								sipLogger.fine("Transition matched: " + t.getId());
								break;
							}
						}
					} else {
						// No transitions defined — implicit match
						sipLogger.fine("No transitions defined, implicit match.");
						matched = new Transition();
					}

					if (matched != null) {
						if (sipLogger.isLoggable(Level.FINE)) {
							sipLogger.fine("Transition: id=" + matched.getId()
									+ ", next=" + matched.getNext()
									+ ", subscriber=" + matched.getSubscriber()
									+ ", routes=" + Arrays.toString(matched.getRoutes()));
						}
						nextApp = matched.createRouterInfo(deployed.get(matched.getNext()), config, request);
					}
				}
			}

			// Default application fallback
			if (previous.equals("null") && (nextApp == null || nextApp.getNextApplicationName() == null
					|| nextApp.getNextApplicationName().equals("null"))) {
				sipLogger.warning("Using defaultApplication: " + config.getDefaultApplication()
						+ " for " + request.getMethod() + " " + request.getRequestURI());
				nextApp = new SipApplicationRouterInfo(deployed.get(config.getDefaultApplication()), region, null, null,
						SipRouteModifier.NO_ROUTE, config);
			}

			if (sipLogger.isLoggable(Level.FINE)) {
				if (nextApp != null) {
					String str = "RouterInfo... " + nextApp.getNextApplicationName();
					str += ", subscriberURI: " + nextApp.getSubscriberURI();
					if (nextApp.getRoutingRegion() != null) {
						str += ", region: " + nextApp.getRoutingRegion().getType();
					}
					str += ", routeModifier: " + nextApp.getRouteModifier();
					str += ", routes: " + Arrays.toString(nextApp.getRoutes());
					sipLogger.fine(str);
				} else {
					sipLogger.fine("No match. Setting router info to null.");
				}
			}

		} catch (Exception e) {
			sipLogger.severe("Error processing request:\n" + request.toString());
			sipLogger.logStackTrace(e);
		}

		return nextApp;
	}

	@Override
	public void applicationDeployed(List<String> apps) {
		sipLogger.info("Application(s) deployed: " + Arrays.toString(apps.toArray()));
		for (String app : apps) {
			deployed.put(SettingsManager.basename(app), app);
		}
	}

	@Override
	public void applicationUndeployed(List<String> apps) {
		sipLogger.info("Application(s) undeployed: " + Arrays.toString(apps.toArray()));
	}

	@Override
	public void destroy() {
		LogManager.closeLogger(FSMAR);
	}

	public static String getDeployedApp(String appName) {
		return deployed.get(SettingsManager.basename(appName));
	}

}
