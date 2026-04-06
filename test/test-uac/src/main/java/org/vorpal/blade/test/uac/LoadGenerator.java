package org.vorpal.blade.test.uac;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Load generation engine for the BLADE Test UAC.
///
/// Each node in the cluster creates its own instance via the REST API.
/// The instance manages a local Java Timer for CPS pacing and tracks
/// its own counters independently. No shared state across nodes.
///
/// Supports two modes:
/// - **CPS mode**: fires calls at a target calls-per-second rate
/// - **Concurrent mode**: maintains N concurrent calls, replenishing as they complete
public class LoadGenerator {

	public enum State {
		STOPPED, RUNNING, STOPPING
	}

	private volatile State state = State.STOPPED;
	private LoadTestRequest request;
	private final AtomicInteger activeCalls = new AtomicInteger(0);
	private final AtomicLong totalStarted = new AtomicLong(0);
	private final AtomicLong totalCompleted = new AtomicLong(0);
	private final AtomicLong totalFailed = new AtomicLong(0);
	private final AtomicLong callIndex = new AtomicLong(0);
	private long startTimeMillis;
	private Timer timer;

	// Resolved effective parameters (config merged with request overrides)
	private String effectiveFromPattern;
	private String effectiveToPattern;
	private String effectiveRequestUri;
	private Map<String, String> effectiveHeaders;
	private String effectiveSdpContent;
	private int effectiveDurationSeconds;

	/// Creates a new load generator instance.
	public LoadGenerator() {
	}

	/// Starts load generation with the given parameters.
	public synchronized void start(LoadTestRequest request) {
		if (state == State.RUNNING) {
			throw new IllegalStateException("Load test already running on this node. Stop it first.");
		}

		this.request = request;
		resolveEffectiveParams();
		resetCounters();

		state = State.RUNNING;
		startTimeMillis = System.currentTimeMillis();

		switch (request.getMode()) {
		case "cps":
			long periodMillis = Math.max(1, (long) (1000.0 / request.getTargetCps()));
			int callsPerTick = 1;

			// For high CPS (> 1000), batch multiple calls per timer tick
			if (request.getTargetCps() > 1000) {
				periodMillis = 1; // 1ms ticks
				callsPerTick = (int) Math.ceil(request.getTargetCps() / 1000.0);
			}

			final int batch = callsPerTick;
			timer = new Timer("load-generator-cps", true);
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
			for (int i = 0; i < request.getTargetConcurrent(); i++) {
				safeGenerateCall();
			}
			break;

		default:
			state = State.STOPPED;
			throw new IllegalArgumentException("Unknown mode: " + request.getMode() + ". Use 'cps' or 'concurrent'.");
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

	/// Returns the current load test status for this node.
	public LoadTestStatus getStatus() {
		LoadTestStatus status = new LoadTestStatus();
		status.setState(state.name());
		status.setActiveCalls(activeCalls.get());
		status.setTotalStarted(totalStarted.get());
		status.setTotalCompleted(totalCompleted.get());
		status.setTotalFailed(totalFailed.get());

		if (request != null) {
			status.setMode(request.getMode());
			status.setTargetCps(request.getTargetCps());
			status.setTargetConcurrent(request.getTargetConcurrent());
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
		replenishIfNeeded();
		checkDrained();
	}

	/// Called when a load-generated call fails (error response or exception).
	public void onCallFailed() {
		activeCalls.decrementAndGet();
		totalFailed.incrementAndGet();
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
		if (request.getMaxCalls() > 0 && totalStarted.get() >= request.getMaxCalls()) {
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

		for (Map.Entry<String, String> h : effectiveHeaders.entrySet()) {
			invite.setHeader(h.getKey(), h.getValue());
		}

		if (effectiveSdpContent != null && !effectiveSdpContent.isEmpty()) {
			invite.setContent(effectiveSdpContent.getBytes(), "application/sdp");
		}

		// Store references for lifecycle callbacks
		appSession.setAttribute("loadGenerator", this);
		appSession.setAttribute("callDuration", effectiveDurationSeconds);

		activeCalls.incrementAndGet();
		totalStarted.incrementAndGet();

		LoadCallflow callflow = new LoadCallflow(this);
		callflow.makeCall(invite);
	}

	private void replenishIfNeeded() {
		if (state == State.RUNNING && "concurrent".equals(request.getMode())) {
			if (activeCalls.get() < request.getTargetConcurrent()) {
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
		UserAgentClientConfig config = UserAgentClientServlet.settingsManager.getCurrent();

		effectiveFromPattern = firstNonEmpty(request.getFromAddressPattern(), config.getFromAddressPattern());
		effectiveToPattern = firstNonEmpty(request.getToAddressPattern(), config.getToAddressPattern());
		effectiveRequestUri = firstNonEmpty(request.getRequestUriTemplate(), config.getRequestUriTemplate());
		effectiveSdpContent = firstNonEmpty(null, config.getSdpContent());

		String durationStr = firstNonEmpty(request.getDuration(), config.getDuration());
		try {
			effectiveDurationSeconds = (durationStr != null) ? config.parseHRDurationAsSeconds(durationStr) : 30;
		} catch (Exception e) {
			effectiveDurationSeconds = 30;
		}

		effectiveHeaders = new HashMap<>();
		if (config.getHeaders() != null) {
			effectiveHeaders.putAll(config.getHeaders());
		}
		if (request.getHeaders() != null) {
			effectiveHeaders.putAll(request.getHeaders());
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
