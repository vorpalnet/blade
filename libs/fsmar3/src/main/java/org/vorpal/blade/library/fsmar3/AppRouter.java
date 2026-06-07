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

	/// Routing metrics, exposed over JMX. Static like `deployed` — one App
	/// Router instance per engine JVM.
	static final Fsmar3Metrics metrics = new Fsmar3Metrics();
	private static final String METRICS_OBJECT_NAME = "org.vorpal.blade:type=Fsmar3,name=metrics";

	/// Last config instance run through [#validateConfiguration] — validate
	/// each loaded config exactly once, on its first routing use (by which
	/// time the `deployed` map is populated). A benign race may validate a
	/// config twice; the log lines just repeat.
	private static volatile AppRouterConfiguration lastValidated;

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

		// Register the metrics MBean — explicitly via StandardMBean (JMX
		// auto-introspection produces broken MBeanInfo for some shapes; see
		// the SettingsManager MBean precedent).
		try {
			javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
			javax.management.ObjectName name = new javax.management.ObjectName(METRICS_OBJECT_NAME);
			if (!mbs.isRegistered(name)) {
				mbs.registerMBean(new javax.management.StandardMBean(metrics, Fsmar3MetricsMBean.class), name);
			}
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("FSMAR metrics MBean registration failed: " + e.getMessage());
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
		metrics.countRequest();

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
				// Validate each loaded config once, on first routing use.
				if (current != lastValidated) {
					validateConfiguration(current);
					lastValidated = current;
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

			// Pseudo-variables: routing metadata published into the context
			// before the state's selectors run (a selector with the same id
			// deliberately overrides). ${previousApp} is refreshed inside the
			// bypass loop as `previous` advances.
			publishPseudoVariables(ctx,
					request.getMethod(),
					(request.getRequestURI() != null) ? request.getRequestURI().toString() : "",
					(directive != null) ? directive.toString() : "",
					(region != null) ? String.valueOf(region.getType()) : "",
					previous,
					request.getCallId());

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

					// Keep ${previousApp} current as the bypass loop advances.
					ctx.put("previousApp", previous);

					// On entry to a state, run its selectors to populate the
					// context from the request (best-effort; never aborts routing).
					if (state != null) {
						state.extract(ctx, request);
					}

					Trigger trigger = (state != null) ? state.getTriggers().get(request.getMethod()) : null;

					Transition matched = null;
					if (trigger != null) {
						boolean trace = sipLogger.isLoggable(Level.FINER);
						for (Transition t : trigger.getTransitions()) {
							boolean fired = t.matches(ctx);
							if (trace) {
								// Route-decision trace: every transition evaluated,
								// in order, with its outcome. The Route Simulator's
								// raw material.
								sipLogger.finer("FSMAR trace: state=" + previous
										+ " trigger=" + request.getMethod()
										+ " transition=" + t.getId()
										+ " when=" + (t.getWhen() != null ? "'" + t.getWhen() + "'" : "(unconditional)")
										+ " -> " + (fired ? "FIRED" : "no match"));
							}
							if (fired) {
								matched = t;
								metrics.countTransition(previous, request.getMethod(), t.getId());
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
						metrics.countCycle();
						sipLogger.warning("FSMAR routing cycle detected at undeployed application '"
								+ next + "'; routing downstream.");
						break;
					}
					metrics.countBypass();
					sipLogger.fine("Bypassing undeployed application '" + next
							+ "'; continuing as though it had already run.");
					previous = next;
				}
			}

			// Default application fallback
			if (previous.equals("null") && (nextApp == null || nextApp.getNextApplicationName() == null
					|| nextApp.getNextApplicationName().equals("null"))) {
				metrics.countDefaultFallback();
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
		try {
			javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
			javax.management.ObjectName name = new javax.management.ObjectName(METRICS_OBJECT_NAME);
			if (mbs.isRegistered(name)) {
				mbs.unregisterMBean(name);
			}
		} catch (Exception ignore) {
			// shutting down anyway
		}
		LogManager.closeLogger(FSMAR);
	}

	public static String getDeployedApp(String appName) {
		return deployed.get(SettingsManager.basename(appName));
	}

	/// Publish routing metadata as pseudo-variables for `when` conditions and
	/// `${}` route templates. Runs before the state's selectors, so a selector
	/// with the same id deliberately overrides.
	///
	/// - `${method}` — SIP method
	/// - `${requestUri}` — request URI
	/// - `${directive}` — routing directive (NEW / CONTINUE / REVERSE)
	/// - `${region}` — routing region type, when the container supplied one
	/// - `${previousApp}` — current FSM state (refreshed as the bypass loop advances)
	/// - `${hour}` — local hour of day, 0–23 (time-of-day routing)
	/// - `${dayOfWeek}` — MONDAY … SUNDAY
	/// - `${hash100}` — stable per-call bucket 0–99 hashed from the Call-ID:
	///   `${hash100} < 5` sends ~5% of calls down a canary path, and every
	///   retransmission/hop of the same call lands in the same bucket
	static void publishPseudoVariables(Context ctx, String method, String requestUri, String directive,
			String regionType, String previous, String callId) {
		if (ctx == null) {
			return;
		}
		ctx.put("method", nullSafe(method));
		ctx.put("requestUri", nullSafe(requestUri));
		ctx.put("directive", nullSafe(directive));
		ctx.put("region", nullSafe(regionType));
		ctx.put("previousApp", nullSafe(previous));

		java.time.LocalDateTime now = java.time.LocalDateTime.now();
		ctx.put("hour", String.valueOf(now.getHour()));
		ctx.put("dayOfWeek", now.getDayOfWeek().name());

		if (callId != null) {
			ctx.put("hash100", String.valueOf(Math.floorMod(callId.hashCode(), 100)));
		}
	}

	private static String nullSafe(String s) {
		return (s != null) ? s : "";
	}

	/// Config sanity pass, run once per loaded configuration on its first
	/// routing use (the `deployed` map is populated by then). Surfaces the
	/// two mistakes that otherwise eat traffic silently:
	///
	/// - a malformed `when` expression (which evaluates to false forever) → SEVERE
	/// - a transition whose `next` names an application that isn't deployed
	///   (legitimate mid-rollout, but worth a WARNING — the bypass logic will
	///   skip it)
	static void validateConfiguration(AppRouterConfiguration config) {
		if (config == null || config.getStates() == null) {
			return;
		}

		for (java.util.Map.Entry<String, State> se : config.getStates().entrySet()) {
			State state = se.getValue();
			if (state == null || state.getTriggers() == null) {
				continue;
			}
			for (java.util.Map.Entry<String, Trigger> te : state.getTriggers().entrySet()) {
				Trigger trigger = te.getValue();
				if (trigger == null || trigger.getTransitions() == null) {
					continue;
				}
				for (Transition t : trigger.getTransitions()) {
					String where = "state '" + se.getKey() + "' trigger " + te.getKey()
							+ " transition '" + t.getId() + "'";

					if (t.getWhen() != null && !t.getWhen().isEmpty()) {
						try {
							new org.vorpal.blade.framework.v3.configuration.expressions.Expression(t.getWhen());
						} catch (Exception e) {
							sipLogger.severe("FSMAR config: malformed 'when' in " + where
									+ " — it will never match: " + e.getMessage());
						}
					}

					String next = t.getNext();
					if (next != null && !deployed.containsKey(next)) {
						sipLogger.warning("FSMAR config: " + where + " routes to '" + next
								+ "', which is not currently deployed (bypass logic will skip it).");
					}
				}
			}
		}

		String def = config.getDefaultApplication();
		if (def != null && !deployed.containsKey(def)) {
			sipLogger.warning("FSMAR config: defaultApplication '" + def + "' is not currently deployed.");
		}
	}

}
