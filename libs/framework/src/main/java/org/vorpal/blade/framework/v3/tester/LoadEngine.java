package org.vorpal.blade.framework.v3.tester;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.servlet.ServletContext;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v3.crud.RuleSet;

/// Load generation engine for the BLADE test apps.
///
/// Each node in the cluster runs its own instance (held on the
/// ServletContext, started via REST or the [TesterMXBean]). The instance
/// manages a local Timer for CPS pacing and tracks its own counters
/// independently — no shared state across nodes.
///
/// Two pacing modes:
/// - **cps** — fires calls at a target calls-per-second rate
/// - **concurrent** — maintains N concurrent calls, replenishing as they
///   complete
///
/// Each generated call runs a [Scenario]: an optional template seeds the
/// INVITE's headers and body, the scenario's rule set transforms requests
/// and responses across the call, and [OriginateCallflow] validates
/// responses, evaluates assertions, and feeds [TesterMetrics].
public class LoadEngine {

	/// ServletContext and SipApplicationSession attribute holding the engine.
	public static final String ATTR = "loadEngine";

	/// SipApplicationSession attribute holding the resolved call duration
	/// (seconds) for auto-BYE.
	public static final String DURATION_ATTR = "callDuration";

	public enum State {
		STOPPED, RUNNING, STOPPING
	}

	private final Supplier<TesterConfiguration> configSupplier;
	private final TemplateLoader templates;
	private final TesterMetrics metrics;

	private volatile State state = State.STOPPED;
	private LoadRequest request;
	private final AtomicInteger activeCalls = new AtomicInteger(0);
	private final AtomicLong totalStarted = new AtomicLong(0);
	private final AtomicLong totalCompleted = new AtomicLong(0);
	private final AtomicLong totalFailed = new AtomicLong(0);
	private final AtomicLong callIndex = new AtomicLong(0);
	private long startTimeMillis;
	private Timer timer;

	// Effective parameters for the current run (config merged with request
	// overrides), resolved once at start().
	private String effectiveMode;
	private double effectiveTargetCps;
	private int effectiveTargetConcurrent;
	private int effectiveMaxCalls;
	private String effectiveFromPattern;
	private String effectiveToPattern;
	private String effectiveRequestUri;
	private Map<String, String> effectiveHeaders;
	private int effectiveDurationSeconds;
	private String effectiveScenarioName;
	private Scenario effectiveScenario;
	private RuleSet effectiveRuleSet;

	public LoadEngine(Supplier<TesterConfiguration> configSupplier, TemplateLoader templates, TesterMetrics metrics) {
		this.configSupplier = configSupplier;
		this.templates = templates;
		this.metrics = metrics;
	}

	/// Returns this deployment's engine, creating it on first use.
	public static LoadEngine from(ServletContext servletContext, Supplier<TesterConfiguration> configSupplier,
			TemplateLoader templates, TesterMetrics metrics) {
		LoadEngine engine = (LoadEngine) servletContext.getAttribute(ATTR);
		if (engine == null) {
			engine = new LoadEngine(configSupplier, templates, metrics);
			servletContext.setAttribute(ATTR, engine);
		}
		return engine;
	}

	/// Starts load generation. Throws [IllegalStateException] when a run is
	/// already active, [IllegalArgumentException] on bad parameters.
	public synchronized void start(LoadRequest request) {
		if (state == State.RUNNING) {
			throw new IllegalStateException("Load test already running on this node. Stop it first.");
		}

		this.request = (request != null) ? request : new LoadRequest();
		resolveEffectiveParams();
		resetCounters();

		state = State.RUNNING;
		startTimeMillis = System.currentTimeMillis();

		switch (effectiveMode) {
		case "cps":
			long periodMillis = Math.max(1, (long) (1000.0 / effectiveTargetCps));
			int callsPerTick = 1;

			// For high CPS (> 1000), batch multiple calls per timer tick
			if (effectiveTargetCps > 1000) {
				periodMillis = 1; // 1ms ticks
				callsPerTick = (int) Math.ceil(effectiveTargetCps / 1000.0);
			}

			final int batch = callsPerTick;
			timer = new Timer("load-engine-cps", true);
			timer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					for (int i = 0; i < batch; i++) {
						safeGenerateCall();
					}
				}
			}, 0, periodMillis);
			break;

		case "concurrent":
			// Fire initial batch — no timer needed, replenishment is callback-driven
			for (int i = 0; i < effectiveTargetConcurrent; i++) {
				safeGenerateCall();
			}
			break;

		default:
			state = State.STOPPED;
			throw new IllegalArgumentException("Unknown mode: " + effectiveMode + ". Use 'cps' or 'concurrent'.");
		}
	}

	/// Stops load generation. Active calls drain naturally.
	public synchronized void stop() {
		if (state == State.STOPPED) {
			return;
		}
		state = State.STOPPING;

		if (timer != null) {
			timer.cancel();
			timer = null;
		}

		if (activeCalls.get() == 0) {
			state = State.STOPPED;
		}
	}

	/// Returns the current load status for this node.
	public LoadStatus getStatus() {
		LoadStatus status = new LoadStatus();
		status.setState(state.name());
		status.setActiveCalls(activeCalls.get());
		status.setTotalStarted(totalStarted.get());
		status.setTotalCompleted(totalCompleted.get());
		status.setTotalFailed(totalFailed.get());

		if (request != null) {
			status.setMode(effectiveMode);
			status.setScenario(effectiveScenarioName);
			status.setTargetCps(effectiveTargetCps);
			status.setTargetConcurrent(effectiveTargetConcurrent);
		}

		if (startTimeMillis > 0 && state != State.STOPPED) {
			status.setElapsedMilliseconds(System.currentTimeMillis() - startTimeMillis);
		}
		return status;
	}

	/// Called when a load-generated call completes (BYE received).
	public void onCallCompleted() {
		activeCalls.decrementAndGet();
		totalCompleted.incrementAndGet();
		if (metrics != null) {
			metrics.scenario(effectiveScenarioName).recordCompleted();
		}
		replenishIfNeeded();
		checkDrained();
	}

	/// Called when a load-generated call fails (error response or exception).
	public void onCallFailed() {
		activeCalls.decrementAndGet();
		totalFailed.incrementAndGet();
		if (metrics != null) {
			metrics.scenario(effectiveScenarioName).recordFailed();
		}
		replenishIfNeeded();
		checkDrained();
	}

	private void safeGenerateCall() {
		try {
			generateCall();
		} catch (Exception e) {
			totalFailed.incrementAndGet();
			Callflow.getSipLogger().severe(e);
		}
	}

	private void generateCall() throws Exception {
		if (state != State.RUNNING) {
			return;
		}
		if (effectiveMaxCalls > 0 && totalStarted.get() >= effectiveMaxCalls) {
			stop();
			return;
		}

		long index = callIndex.getAndIncrement();
		SipFactory sipFactory = Callflow.getSipFactory();
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		String from = resolvePattern(effectiveFromPattern, index);
		String to = resolvePattern(effectiveToPattern, index);

		SipServletRequest invite = sipFactory.createRequest(appSession, "INVITE", from, to);

		if (effectiveRequestUri != null && !effectiveRequestUri.isEmpty()) {
			invite.setRequestURI(sipFactory.createURI(resolvePattern(effectiveRequestUri, index)));
		}

		// Template first (headers + body seed), then per-run headers, then
		// the scenario's rule set inside OriginateCallflow — so rules can
		// rewrite whatever the template and headers produced.
		if (effectiveScenario != null && effectiveScenario.getTemplate() != null) {
			templates.get(effectiveScenario.getTemplate()).apply(invite);
		}

		for (Map.Entry<String, String> h : effectiveHeaders.entrySet()) {
			invite.setHeader(h.getKey(), h.getValue());
		}

		// References and variables for lifecycle callbacks, rules, and
		// assertions.
		appSession.setAttribute(ATTR, this);
		appSession.setAttribute(DURATION_ATTR, effectiveDurationSeconds);
		appSession.setAttribute("index", String.valueOf(index));
		if (effectiveScenarioName != null) {
			appSession.setAttribute("scenario", effectiveScenarioName);
		}

		activeCalls.incrementAndGet();
		totalStarted.incrementAndGet();
		if (metrics != null) {
			metrics.scenario(effectiveScenarioName).recordStarted();
		}

		OriginateCallflow callflow = new OriginateCallflow(this, metrics, effectiveScenario, effectiveScenarioName,
				effectiveRuleSet);
		callflow.makeCall(invite);
	}

	private void replenishIfNeeded() {
		if (state == State.RUNNING && "concurrent".equals(effectiveMode)) {
			if (activeCalls.get() < effectiveTargetConcurrent) {
				safeGenerateCall();
			}
		}
	}

	private void checkDrained() {
		if (state == State.STOPPING && activeCalls.get() == 0) {
			state = State.STOPPED;
		}
	}

	private void resolveEffectiveParams() {
		TesterConfiguration config = (configSupplier != null) ? configSupplier.get() : null;
		OriginateSettings originate = (config != null && config.getOriginate() != null) ? config.getOriginate()
				: new OriginateSettings();
		LoadSettings load = (originate.getLoad() != null) ? originate.getLoad() : new LoadSettings();

		effectiveMode = (request.getMode() != null && !request.getMode().isEmpty()) ? request.getMode()
				: load.getMode();
		effectiveTargetCps = (request.getTargetCps() != null) ? request.getTargetCps() : load.getTargetCps();
		effectiveTargetConcurrent = (request.getTargetConcurrent() != null) ? request.getTargetConcurrent()
				: load.getTargetConcurrent();
		effectiveMaxCalls = (request.getMaxCalls() != null) ? request.getMaxCalls() : load.getMaxCalls();

		effectiveFromPattern = firstNonEmpty(request.getFromAddressPattern(), originate.getFromAddressPattern());
		effectiveToPattern = firstNonEmpty(request.getToAddressPattern(), originate.getToAddressPattern());
		effectiveRequestUri = firstNonEmpty(request.getRequestUriTemplate(), originate.getRequestUriTemplate());

		String durationStr = firstNonEmpty(request.getDuration(), originate.getDuration());
		long durationMs = ResponseScript.parseMillis(durationStr);
		effectiveDurationSeconds = (durationMs > 0) ? (int) (durationMs / 1000L) : 30;

		effectiveHeaders = new HashMap<>();
		if (request.getHeaders() != null) {
			effectiveHeaders.putAll(request.getHeaders());
		}

		effectiveScenarioName = firstNonEmpty(request.getScenario(), originate.getScenario());
		effectiveScenario = null;
		effectiveRuleSet = null;
		if (effectiveScenarioName != null && config != null) {
			effectiveScenario = config.getScenarios().get(effectiveScenarioName);
			if (effectiveScenario == null) {
				throw new IllegalArgumentException("Unknown scenario: " + effectiveScenarioName);
			}
			if (effectiveScenario.getRuleSet() != null
					&& config.getRuleSets().get(effectiveScenario.getRuleSet()) == null) {
				throw new IllegalArgumentException("Scenario '" + effectiveScenarioName + "' names unknown ruleSet: "
						+ effectiveScenario.getRuleSet());
			}
			effectiveRuleSet = effectiveScenario.effectiveRules(config);
		}
	}

	private void resetCounters() {
		activeCalls.set(0);
		totalStarted.set(0);
		totalCompleted.set(0);
		totalFailed.set(0);
		callIndex.set(0);
		startTimeMillis = 0;
	}

	private String resolvePattern(String pattern, long index) {
		if (pattern == null) {
			return null;
		}
		return pattern.replace("${index}", String.valueOf(index));
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if (v != null && !v.isEmpty()) {
				return v;
			}
		}
		return null;
	}
}
