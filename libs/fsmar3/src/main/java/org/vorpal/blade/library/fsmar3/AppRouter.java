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

import org.vorpal.blade.framework.v2.AsyncSipServlet;
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

		// Correlation id for the single per-invocation log line, pulled from the
		// X-Vorpal-ID header. Session-free by necessity: the App Router must not
		// touch the Coherence-backed SipApplicationSession/SipSession (the
		// session-aware Logger overloads resolve a session that isn't safely there).
		// Isolated in its own try so a header-read hiccup can never reach
		// getNextApplication's catch below — which returns null and hands OCCAS 8.3
		// the very 500 we just eliminated. null on a brand-new external INVITE (not
		// stamped until the first BLADE hop); the line then falls back to Call-ID.
		String vsession = null;
		String vdialog = null;
		try {
			vsession = AsyncSipServlet.getVorpalSessionIdFromMessage(request);
			vdialog = AsyncSipServlet.getVorpalDialogIdFromMessage(request);
		} catch (Exception ignore) {
			// best-effort correlation id; never affects routing
		}

		try {
			// stateInfo carries the pinned config snapshot + the accumulating
			// extraction context across every hop of this initial request.
			// First call: saved is null, so load fresh config below.
			RoutingState routingState = (RoutingState) saved;
			// First App Router consultation for this initial request: the container
			// hands back no stateInfo. FSMAR always returns a non-null RoutingState as
			// its stateInfo (see the SipApplicationRouterInfo constructions below), so
			// saved == null marks the start of a fresh composition — and only then.
			boolean freshComposition = (routingState == null);
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

			// Determine the previous application — the FSM state to evaluate from.
			//
			// On a fresh composition the chain starts at the "null" state. We must
			// NOT read the previous app off the session here: for a second initial
			// INVITE that the container has joined to an existing SipApplicationSession
			// via an @SipApplicationKey (e.g. a second SIPREC recording dialog keyed on
			// the same UCID), getSipApplicationSessionImpl() returns the session owned
			// by the LAST application in the chain. Starting the FSM from that terminal
			// state finds no forward INVITE transition, getNextApplication returns null,
			// and OCCAS 8.3 renders that as a 500 "No handler found" (8.0 tolerated the
			// null AR result; the default-application fallback below is likewise gated
			// on previous == "null"). Only trust the session's application name on
			// continuation hops, where the container hands our stateInfo back.
			String previous;
			if (freshComposition) {
				previous = "null";
			} else {
				SipApplicationSessionImpl sasi = ((SipServletRequestAdapter) request)
						.getImpl().getSipApplicationSessionImpl();
				previous = (sasi != null) ? SettingsManager.basename(sasi.getApplicationName()) : "null";
			}

			// Trace capture (opt-in, armed via the metrics MBean): null in the
			// common disarmed case. Begin the hop before the pseudo-variables
			// publish so they show up in the hop's extracted-values diff.
			RouteTrace trace = metrics.recorder(request.getCallId(), request.getMethod(),
					(request.getRequestURI() != null) ? request.getRequestURI().toString() : "");
			if (trace != null) {
				trace.beginHop(previous, routingState.getContext());
			}

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

			// Single-line summary, assembled as routing proceeds and emitted exactly
			// once at the end of this invocation. `startState` preserves the FSM
			// entry state for the line (the bypass loop may advance `previous`).
			final String startState = previous;
			String decision = "none (routed downstream)";
			java.util.List<String> bypassed = new java.util.ArrayList<>();

			// For targeted sessions, route directly
			if (requestInfo != null) {
				String deployedApp = getDeployedApp(requestInfo.getApplicationName());
				nextApp = new SipApplicationRouterInfo(deployedApp, region, null, null,
						SipRouteModifier.NO_ROUTE, routingState);
				decision = "targeted=" + requestInfo.getApplicationName() + " -> " + deployedApp;
				if (trace != null) {
					trace.routed(nextApp);
				}
			}

			// URI-based direct routing (e.g. "sip:hold"). Guard the cast: a
			// tel: Request-URI is not a SipURI, and a ClassCastException here
			// would escape to the outer catch — null return, silent 500.
			if (nextApp == null && request.getRequestURI() instanceof SipURI) {
				SipURI sipUri = (SipURI) request.getRequestURI();
				if (null == sipUri.getUser()) {
					String app = getDeployedApp(sipUri.getHost());
					if (app != null) {
						nextApp = new SipApplicationRouterInfo(app, region, null, null,
								SipRouteModifier.NO_ROUTE, routingState);
						decision = "uri=" + sipUri.getHost() + " -> " + app;
						if (trace != null) {
							trace.routed(nextApp);
						}
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

				boolean firstIteration = true;
				while (true) {
					// A bypass iteration is a new hop in the captured trace
					// (the first iteration's hop is already open).
					if (trace != null && !firstIteration) {
						trace.beginHop(previous, routingState.getContext());
					}
					firstIteration = false;

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
						for (Transition t : trigger.getTransitions()) {
							boolean fired = t.matches(ctx);
							if (trace != null) {
								// Every transition evaluated, in order, with its
								// outcome — the Route Simulator's raw material.
								trace.evaluated(t.getId(), t.getWhen(), fired);
							}
							if (fired) {
								matched = t;
								metrics.countTransition(previous, request.getMethod(), t.getId());
								break;
							}
						}
						if (matched == null && trigger.getTransitions().isEmpty()) {
							// Trigger defined with no transitions — implicit match, no action.
							matched = new Transition();
						}
					}

					if (matched == null) {
						break;
					}
					if (trace != null) {
						trace.matched(matched.getId(), matched.getNext());
					}

					String next = matched.getNext();
					String deployedApp = (next != null) ? deployed.get(next) : null;
					if (deployedApp != null) {
						nextApp = matched.createRouterInfo(deployedApp, routingState, ctx, request);
						decision = "matched=" + matched.getId() + " -> " + deployedApp;
						if (trace != null) {
							trace.routed(nextApp);
						}
						break;
					}

					if (next == null) {
						// Matched with no target application — nothing to route to.
						decision = "matched=" + matched.getId() + " -> (no target, routed downstream)";
						break;
					}

					// 'next' names an undeployed application: bypass it and continue
					// from that state. Stop if we'd revisit a state (config loop).
					if (!visited.add(next)) {
						metrics.countCycle();
						if (trace != null) {
							trace.cycle();
						}
						bypassed.add(next + " (cycle)");
						break;
					}
					metrics.countBypass();
					if (trace != null) {
						trace.bypassed();
					}
					bypassed.add(next);
					previous = next;
				}
			}

			// Default application fallback. defaultApplication is optional —
			// guard it: ConcurrentHashMap.get(null) throws NPE, which would
			// escape to the outer catch as a silent 500. With no default
			// configured, nextApp stays null and OCCAS routes downstream.
			if (config.getDefaultApplication() != null
					&& previous.equals("null") && (nextApp == null || nextApp.getNextApplicationName() == null
					|| nextApp.getNextApplicationName().equals("null"))) {
				metrics.countDefaultFallback();
				if (trace != null) {
					trace.defaultFallback(config.getDefaultApplication());
				}
				decision = "defaultApplication -> " + config.getDefaultApplication();
				nextApp = new SipApplicationRouterInfo(deployed.get(config.getDefaultApplication()), region, null, null,
						SipRouteModifier.NO_ROUTE, routingState);
			}

			// Close out this invocation's captured hop and snapshot the context.
			if (trace != null) {
				trace.endInvocation(routingState.getContext());
			}

			// Exactly one line per invocation describing the routing decision,
			// correlated by X-Vorpal-ID ([session:dialog]) so a call can be grepped
			// end-to-end across BLADE services. Built only when the level is live —
			// the core log() does not pre-gate and this runs at 1000+ CPS.
			if (sipLogger != null && sipLogger.isLoggable(Level.INFO)) {
				StringBuilder sb = new StringBuilder("FSMAR ");
				sb.append(request.getMethod()).append(' ').append(request.getRequestURI());
				sb.append(" previous=").append(startState);
				sb.append(" directive=").append(directive != null ? directive : "-");
				sb.append(" region=").append(region != null ? region.getType() : "-");
				if (requestInfo != null) {
					sb.append(" requestInfo=").append(requestInfo.getType());
				}
				sb.append(" -> ").append(decision);
				if (!bypassed.isEmpty()) {
					sb.append(" bypassed=").append(bypassed);
				}
				if (nextApp != null && nextApp.getRoutes() != null && nextApp.getRoutes().length > 0) {
					sb.append(" routes=").append(Arrays.toString(nextApp.getRoutes()));
				}
				sb.append(" callid=").append(request.getCallId());
				sipLogger.log(Level.INFO, vsession, vdialog, sb.toString());
			}

		} catch (Exception e) {
			// Still one line — correlated and self-describing — then the trace.
			// Logger can be null only if init() failed; fall back to stderr so
			// the REAL exception isn't masked by a logging NPE.
			if (sipLogger != null) {
				sipLogger.log(Level.SEVERE, vsession, vdialog, "FSMAR " + request.getMethod()
						+ " " + request.getRequestURI() + " callid=" + request.getCallId()
						+ " -> ERROR: " + e);
				sipLogger.logStackTrace(e);
			} else {
				e.printStackTrace();
			}
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
		// basename(null) and ConcurrentHashMap.get(null) both throw NPE.
		return (appName != null) ? deployed.get(SettingsManager.basename(appName)) : null;
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
