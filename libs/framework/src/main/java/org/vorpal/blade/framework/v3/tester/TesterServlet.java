package org.vorpal.blade.framework.v3.tester;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v3.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v3.media.CallflowHold;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v3.crud.RuleSet;

/// Abstract base for BLADE test apps (test-uac, test-uas). Resolves a
/// [Scenario] for every initial INVITE, then routes the call by the
/// scenario's role:
///
/// - `answer` → [ScriptedAnswer] plays the response script locally
///   (in-dialog requests get hold / 200 / 501 handling)
/// - `b2bua` (and anything else) → standard B2BUA passthrough, with the
///   scenario's template and rule set applied across the lifecycle events
///
/// ## Scenario resolution, in priority order
///
/// 1. `scenario=` Request-URI parameter
/// 2. translation plan match carrying a `scenario` attribute — or a bare
///    `ruleSet` attribute, which acts as an unnamed b2bua scenario (drop-in
///    CRUD-service compatibility)
/// 3. `status=` / `delay=` / `refer=` Request-URI shorthands → ephemeral
///    answer scenario (backward compatible with existing test scripts)
/// 4. the configuration's `defaultScenario`
/// 5. [#defaultScenario] — the subclass's built-in default
///
/// Subclasses implement [#testerConfiguration] and call [#initTester] /
/// [#destroyTester] from their servlet lifecycle hooks. Originated-call
/// completion notifications flow through [#callCompleted] /
/// [#callDeclined] to the [LoadEngine], so subclasses overriding those must
/// call super.
public abstract class TesterServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;

	/// App-session attributes stamped on the initial INVITE.
	public static final String SCENARIO_ATTR = "tester.scenario";
	public static final String SCENARIO_NAME_ATTR = "tester.scenarioName";
	public static final String RULESET_ATTR = "tester.ruleSet";
	public static final String ENDPOINT_ATTR = "tester.endpoint";

	protected transient TemplateLoader templateLoader;
	protected transient TesterMetrics metrics;
	protected transient LoadEngine loadEngine;
	private transient TesterControl testerControl;

	/// The current configuration — typically
	/// `settingsManager.getCurrent()` from the subclass.
	protected abstract TesterConfiguration testerConfiguration();

	/// Wires the tester machinery: template loader, metrics, load engine,
	/// and the per-node [TesterControl] MBean. Call from `servletCreated`
	/// AFTER creating the SettingsManager (the MBean name and cluster come
	/// from it). `name` is the flattened context name, e.g.
	/// `settingsManager.getServletContextName()`.
	protected void initTester(SipServletContextEvent event, String name) {
		templateLoader = new TemplateLoader();
		metrics = TesterMetrics.from(event.getServletContext());
		loadEngine = LoadEngine.from(event.getServletContext(), this::testerConfiguration, templateLoader, metrics);
		try {
			testerControl = new TesterControl(loadEngine, metrics);
			testerControl.register(name);
		} catch (Exception e) {
			sipLogger.severe("TesterServlet: TesterControl MBean registration failed");
			sipLogger.logStackTrace(e);
		}
	}

	/// Stops any running load and unregisters the control MBean. Call from
	/// `servletDestroyed`.
	protected void destroyTester() {
		if (loadEngine != null) {
			loadEngine.stop();
		}
		if (testerControl != null) {
			try {
				testerControl.unregister();
			} catch (Exception e) {
				sipLogger.logStackTrace(e);
			}
		}
	}

	/// Subclass hook: the built-in default scenario when nothing else
	/// matched (no URI parameter, no translation, no shorthand, no
	/// configured defaultScenario). Null means plain B2BUA passthrough.
	protected Scenario defaultScenario(SipServletRequest request) {
		return null;
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		if (request.getMethod().equals("INVITE") && request.isInitial()) {
			return chooseInitialInvite(request);
		}

		// In-dialog / non-initial: route by the mode stamped on the INVITE.
		Boolean endpoint = (Boolean) request.getApplicationSession().getAttribute(ENDPOINT_ATTR);
		if (!Boolean.TRUE.equals(endpoint)) {
			return super.chooseCallflow(request); // B2BUA: in-dialog forwarding
		}

		switch (request.getMethod()) {
		case "INVITE":
			return new CallflowHold(); // re-INVITE → inactive hold (RFC 3264)
		case "BYE":
		case "CANCEL":
		case "INFO":
			return new OkResponse();
		default:
			return new NotImplemented();
		}
	}

	private Callflow chooseInitialInvite(SipServletRequest request) throws ServletException, IOException {
		TesterConfiguration config = testerConfiguration();
		SipApplicationSession appSession = request.getApplicationSession();
		URI ruri = request.getRequestURI();

		String name = null;
		Scenario scenario = null;

		// 1. Explicit scenario= URI parameter.
		String param = ruri.getParameter("scenario");
		if (param != null && config != null) {
			scenario = config.getScenarios().get(param);
			name = param;
			if (scenario == null) {
				sipLogger.warning(request, "TesterServlet: unknown scenario '" + param + "' in Request-URI");
			}
		}

		// 2. Translation plan: a `scenario` attribute, or a bare `ruleSet`
		// attribute acting as an unnamed b2bua scenario.
		if (scenario == null && config != null) {
			Translation translation = config.findTranslation(request);
			if (translation != null) {
				String scenarioAttr = (String) translation.getAttribute("scenario");
				if (scenarioAttr != null) {
					scenario = config.getScenarios().get(scenarioAttr);
					name = scenarioAttr;
					if (scenario == null) {
						sipLogger.warning(request,
								"TesterServlet: translation names unknown scenario '" + scenarioAttr + "'");
					}
				}
				if (scenario == null) {
					String ruleSetAttr = (String) translation.getAttribute("ruleSet");
					if (ruleSetAttr != null && config.getRuleSets().get(ruleSetAttr) != null) {
						scenario = new Scenario();
						scenario.setRole(Scenario.ROLE_B2BUA);
						scenario.setRuleSet(ruleSetAttr);
						name = ruleSetAttr;
					}
				}
			}
		}

		// 3. status= / delay= / refer= shorthands.
		if (scenario == null) {
			scenario = shorthandScenario(request, ruri);
			if (scenario != null) {
				name = "(uri-params)";
			}
		}

		// 4. Configured default.
		if (scenario == null && config != null && config.getDefaultScenario() != null) {
			scenario = config.getScenarios().get(config.getDefaultScenario());
			name = config.getDefaultScenario();
			if (scenario == null) {
				sipLogger.warning(request,
						"TesterServlet: defaultScenario '" + config.getDefaultScenario() + "' is not defined");
			}
		}

		// 5. Built-in default.
		if (scenario == null) {
			scenario = defaultScenario(request);
			if (scenario != null) {
				name = "(default)";
			}
		}

		boolean endpoint = false;
		if (scenario != null) {
			appSession.setAttribute(SCENARIO_ATTR, scenario);
			appSession.setAttribute(SCENARIO_NAME_ATTR, name);
			appSession.setAttribute("scenario", name); // ${scenario} variable

			RuleSet ruleSet = resolveRules(config, scenario, name);
			if (ruleSet != null) {
				appSession.setAttribute(RULESET_ATTR, ruleSet);
			}

			endpoint = Scenario.ROLE_ANSWER.equals(scenario.getRole());
		}
		appSession.setAttribute(ENDPOINT_ATTR, endpoint);

		if (endpoint) {
			return new ScriptedAnswer(scenario, name, metrics);
		}
		return super.chooseCallflow(request); // B2BUA passthrough
	}

	/// Synthesizes an ephemeral answer scenario from the `status` / `delay`
	/// / `refer` Request-URI parameters — the original test-uas contract,
	/// preserved for existing test scripts.
	protected Scenario shorthandScenario(SipServletRequest request, URI ruri) {
		String status = ruri.getParameter("status");
		String delay = ruri.getParameter("delay");
		String refer = ruri.getParameter("refer");
		if (status == null && delay == null && refer == null) {
			return null;
		}

		Scenario scenario = new Scenario();
		scenario.setRole(Scenario.ROLE_ANSWER);
		ResponseScript script = new ResponseScript();

		if (refer != null) {
			// Answer 200, then transfer; the status param rides along on the
			// Refer-To so the transfer target knows what to answer with.
			script.getSend().add(new ResponseStep(200));
			script.setRefer(refer);
			script.setReferStatus(status != null ? status : "200");
		} else {
			int statusCode = 200;
			if (status != null) {
				try {
					statusCode = Integer.parseInt(status);
				} catch (NumberFormatException e) {
					sipLogger.warning(request, "TesterServlet: unparseable status '" + status + "', using 200");
				}
			}
			script.getSend().add(new ResponseStep(statusCode));
			if (delay != null) {
				script.setAutoByeAfter(delay);
			}
		}

		scenario.setResponseScript(script);
		return scenario;
	}

	/// The scenario's effective rule set ([Scenario#effectiveRules]), with a
	/// warning for dangling `ruleSet` references.
	protected RuleSet resolveRules(TesterConfiguration config, Scenario scenario, String name) {
		if (scenario.getRuleSet() != null && (config == null || config.getRuleSets().get(scenario.getRuleSet()) == null)) {
			sipLogger.warning(
					"TesterServlet: scenario '" + name + "' names unknown ruleSet '" + scenario.getRuleSet() + "'");
		}
		return scenario.effectiveRules(config);
	}

	private void applyRules(SipServletMessage message, String lifecycleEvent) {
		RuleSet ruleSet = (RuleSet) message.getApplicationSession().getAttribute(RULESET_ATTR);
		if (ruleSet != null) {
			ruleSet.applyRules(message, lifecycleEvent);
		}
	}

	private Scenario scenario(SipServletMessage message) {
		return (Scenario) message.getApplicationSession().getAttribute(SCENARIO_ATTR);
	}

	private String scenarioName(SipServletMessage message) {
		return (String) message.getApplicationSession().getAttribute(SCENARIO_NAME_ATTR);
	}

	/// Forwards completion notifications for originated calls to the
	/// [LoadEngine], same wiring the original test-uac used.
	private LoadEngine engineFor(SipServletMessage message) {
		Object attr = message.getApplicationSession().getAttribute(LoadEngine.ATTR);
		return (attr instanceof LoadEngine) ? (LoadEngine) attr : null;
	}

	// ------------------------------------------------------------
	// B2BUA lifecycle: template + rules on the outbound leg
	// ------------------------------------------------------------

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		Scenario scenario = scenario(outboundRequest);
		if (scenario != null && scenario.getTemplate() != null) {
			try {
				templateLoader.get(scenario.getTemplate()).apply(outboundRequest);
			} catch (Exception e) {
				sipLogger.warning(outboundRequest,
						"TesterServlet: template '" + scenario.getTemplate() + "' failed: " + e.getMessage());
			}
		}
		applyRules(outboundRequest, "callStarted");
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		applyRules(outboundResponse, "callAnswered");
		if (metrics != null && scenarioName(outboundResponse) != null) {
			metrics.scenario(scenarioName(outboundResponse)).recordForwarded(outboundResponse.getStatus());
		}
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callConnected");
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callCompleted");
		LoadEngine engine = engineFor(outboundRequest);
		if (engine != null) {
			engine.onCallCompleted();
		}
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		applyRules(outboundResponse, "callDeclined");
		if (metrics != null && scenarioName(outboundResponse) != null) {
			metrics.scenario(scenarioName(outboundResponse)).recordForwarded(outboundResponse.getStatus());
		}
		LoadEngine engine = engineFor(outboundResponse);
		if (engine != null) {
			engine.onCallFailed();
		}
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "callAbandoned");
	}

	@Override
	public void requestEvent(SipServletRequest outboundRequest) throws ServletException, IOException {
		applyRules(outboundRequest, "requestEvent");
	}

	@Override
	public void responseEvent(SipServletResponse outboundResponse) throws ServletException, IOException {
		applyRules(outboundResponse, "responseEvent");
	}
}
