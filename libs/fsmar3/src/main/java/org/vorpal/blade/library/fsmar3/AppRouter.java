package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

import com.bea.wcp.sip.engine.SipServletRequestAdapter;
import com.bea.wcp.sip.engine.server.SipApplicationSessionImpl;

/// FSMAR v3 Application Router implementation.
///
/// Evaluates a finite state machine to determine which SIP application should
/// handle each incoming request. On entry to a state, its `Selector`s extract
/// values from the request into a routing [Context]; transitions then fire on
/// `when` conditions over those values and may build `${}`-templated routes.
/// State (the config snapshot plus the accumulating context) is carried across
/// hops in the JSR-289 `stateInfo` — see [RoutingState].
public class AppRouter implements SipApplicationRouter {

	protected static String FSMAR = "fsmar3";
	public static Logger sipLogger;
	private static SettingsManager<AppRouterConfiguration> settingsManager;
	protected static ConcurrentHashMap<String, String> deployed = new ConcurrentHashMap<>();

	@Override
	public void init() {
		try {
			settingsManager = new SettingsManager<>(FSMAR, AppRouterConfiguration.class,
					new AppRouterConfigurationSample());
			sipLogger = SettingsManager.getSipLogger();
		} catch (Exception e) {
			e.printStackTrace();
			if (sipLogger != null) {
				sipLogger.severe(e);
			}
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
			// stateInfo carries the pinned config snapshot + the accumulating
			// extraction context across every hop of this initial request.
			// First call: saved is null, so load fresh config below.
			RoutingState routingState = (RoutingState) saved;
			if (routingState == null) {
				AppRouterConfiguration current = settingsManager.getCurrent();
				if (current == null) {
					throw new Exception("Invalid FSMAR configuration file.");
				}
				routingState = new RoutingState(current);
			}
			AppRouterConfiguration config = routingState.getConfig();

			// Map-backed context over the carried map: selectors write here,
			// conditions and ${} route templates read here.
			Context ctx = new MemoryContext(routingState.getContext());

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
						SipRouteModifier.NO_ROUTE, routingState);
			}

			// URI-based direct routing (e.g. "sip:hold")
			if (nextApp == null) {
				SipURI sipUri = (SipURI) request.getRequestURI();
				if (null == sipUri.getUser()) {
					String app = getDeployedApp(sipUri.getHost());
					if (app != null) {
						nextApp = new SipApplicationRouterInfo(app, region, null, null,
								SipRouteModifier.NO_ROUTE, routingState);
					}
				}
			}

			// Evaluate the state machine. If a matched transition targets an
			// application that isn't currently deployed, bypass it: treat that
			// application as the new "previous" state and keep evaluating, as
			// though it had already run. Continue until we land on a deployed
			// application or run out of matching transitions — in the latter
			// case nextApp stays null and OCCAS routes the request downstream.
			// The visited set guards against config that loops back on itself.
			if (nextApp == null) {
				Set<String> visited = new HashSet<>();
				visited.add(previous);

				while (true) {
					State state = config.getStates().get(previous);

					// On entry to a state, run its selectors to populate the
					// context from the request (best-effort; never aborts routing).
					if (state != null) {
						state.extract(ctx, request);
					}

					Trigger trigger = (state != null) ? state.getTriggers().get(request.getMethod()) : null;

					Transition matched = null;
					if (trigger != null) {
						for (Transition t : trigger.getTransitions()) {
							if (t.matches(ctx)) {
								matched = t;
								sipLogger.fine("Transition matched: " + t.getId());
								break;
							}
						}
						if (matched == null && trigger.getTransitions().isEmpty()) {
							// Trigger defined with no transitions — implicit match, no action.
							sipLogger.fine("No transitions defined, implicit match.");
							matched = new Transition();
						}
					}

					if (matched == null) {
						break;
					}

					if (sipLogger.isLoggable(Level.FINE)) {
						sipLogger.fine("Transition: id=" + matched.getId()
								+ ", next=" + matched.getNext()
								+ ", subscriber=" + matched.getSubscriber()
								+ ", routes=" + Arrays.toString(matched.getRoutes()));
					}

					String next = matched.getNext();
					String deployedApp = (next != null) ? deployed.get(next) : null;
					if (deployedApp != null) {
						nextApp = matched.createRouterInfo(deployedApp, routingState, ctx, request);
						break;
					}

					if (next == null) {
						// Matched with no target application — nothing to route to.
						break;
					}

					// 'next' names an undeployed application: bypass it and continue
					// from that state. Stop if we'd revisit a state (config loop).
					if (!visited.add(next)) {
						sipLogger.warning("FSMAR routing cycle detected at undeployed application '"
								+ next + "'; routing downstream.");
						break;
					}
					sipLogger.fine("Bypassing undeployed application '" + next
							+ "'; continuing as though it had already run.");
					previous = next;
				}
			}

			// Default application fallback
			if (previous.equals("null") && (nextApp == null || nextApp.getNextApplicationName() == null
					|| nextApp.getNextApplicationName().equals("null"))) {
				sipLogger.warning("Using defaultApplication: " + config.getDefaultApplication()
						+ " for " + request.getMethod() + " " + request.getRequestURI());
				nextApp = new SipApplicationRouterInfo(deployed.get(config.getDefaultApplication()), region, null, null,
						SipRouteModifier.NO_ROUTE, routingState);
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
		for (String app : apps) {
			deployed.remove(SettingsManager.basename(app));
		}
	}

	@Override
	public void destroy() {
		LogManager.closeLogger(FSMAR);
	}

	public static String getDeployedApp(String appName) {
		return deployed.get(SettingsManager.basename(appName));
	}

}
