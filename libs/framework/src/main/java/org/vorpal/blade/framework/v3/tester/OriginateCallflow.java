package org.vorpal.blade.framework.v3.tester;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.crud.Rule;
import org.vorpal.blade.framework.v3.crud.RuleSet;

/// Per-call lifecycle handler for originated (load-generated) calls.
///
/// Sends the INVITE (after the scenario's request-side rules run), applies
/// response-side rules to every response, ACKs on success, schedules
/// auto-BYE after the call duration, records latency / final-status /
/// expectation metrics, and evaluates the scenario's assertions when the
/// final response arrives. Call completion and decline notifications reach
/// the [LoadEngine] through the servlet's B2BUA lifecycle callbacks, as
/// they always have.
public class OriginateCallflow extends ClientCallflow {
	private static final long serialVersionUID = 1L;

	/// Variables synthesized for rules and assertions, on the app session.
	public static final String VAR_LAST_STATUS = "lastStatus";
	public static final String VAR_STATUS_SEQUENCE = "statusSequence";
	public static final String VAR_SETUP_MS = "setupMs";

	private transient LoadEngine engine;
	private transient TesterMetrics metrics;
	private final Scenario scenario;
	private final String scenarioName;
	private final RuleSet ruleSet;
	private long sentAtMillis;

	public OriginateCallflow(LoadEngine engine, TesterMetrics metrics, Scenario scenario, String scenarioName,
			RuleSet ruleSet) {
		this.engine = engine;
		this.metrics = metrics;
		this.scenario = scenario;
		this.scenarioName = scenarioName;
		this.ruleSet = ruleSet;
	}

	/// Sends the INVITE and manages the full call lifecycle.
	public void makeCall(SipServletRequest invite) throws ServletException, IOException {
		final long byeAfterMs = byeAfterMillis(invite);

		if (ruleSet != null) {
			ruleSet.applyRules(invite, "callStarted");
		}

		sentAtMillis = System.currentTimeMillis();

		sendRequest(invite, (response) -> {
			try {
				recordStatusSequence(invite.getApplicationSession(), response.getStatus());
				if (ruleSet != null) {
					ruleSet.applyRules(response, "responseEvent");
				}
				if (provisional(response)) {
					return;
				}
				onFinalResponse(invite, response, byeAfterMs);
			} catch (Exception e) {
				sipLogger.logStackTrace(e);
				notifyFailed();
			}
		});
	}

	private void onFinalResponse(SipServletRequest invite, SipServletResponse response, long byeAfterMs)
			throws ServletException, IOException {
		long setupMs = System.currentTimeMillis() - sentAtMillis;
		int status = response.getStatus();

		SipApplicationSession appSession = invite.getApplicationSession();
		appSession.setAttribute(VAR_LAST_STATUS, String.valueOf(status));
		appSession.setAttribute(VAR_SETUP_MS, String.valueOf(setupMs));

		if (metrics != null) {
			ScenarioStats stats = metrics.scenario(scenarioName);
			stats.recordFinal(status, setupMs);
			if (!Rule.matchesStatus(expectFinal(), status)) {
				stats.recordExpectMismatch();
				sipLogger.warning(response, "OriginateCallflow[" + scenarioName + "]: final status " + status
						+ " does not match expectFinal '" + expectFinal() + "'");
			}
			evaluateAssertions(invite, stats);
		}

		if (successful(response)) {
			sendRequest(response.createAck());

			// Schedule auto-BYE after the call duration.
			if (byeAfterMs > 0) {
				startTimer(appSession, byeAfterMs, false, (timer) -> {
					SipSession session = invite.getSession();
					if (session != null && session.isValid() && session.getState() != SipSession.State.TERMINATED) {
						try {
							sendRequest(session.createRequest(BYE));
						} catch (Exception e) {
							sipLogger.logStackTrace(e);
							notifyFailed();
						}
					}
				});
			}
		}

		if (failure(response)) {
			notifyFailed();
		}
		// Completed calls are handled by the servlet's callCompleted().
	}

	private String expectFinal() {
		if (scenario != null && scenario.getResponseScript() != null
				&& scenario.getResponseScript().getExpectFinal() != null) {
			return scenario.getResponseScript().getExpectFinal();
		}
		return "2xx";
	}

	/// The scenario's `autoByeAfter` wins over the engine-resolved duration
	/// stamped on the app session.
	private long byeAfterMillis(SipServletRequest invite) {
		if (scenario != null && scenario.getResponseScript() != null
				&& scenario.getResponseScript().getAutoByeAfter() != null) {
			return ResponseScript.parseMillis(scenario.getResponseScript().getAutoByeAfter());
		}
		Object attr = invite.getApplicationSession().getAttribute(LoadEngine.DURATION_ATTR);
		if (attr instanceof Integer) {
			return ((Integer) attr) * 1000L;
		}
		return 30_000L;
	}

	private void evaluateAssertions(SipServletRequest invite, ScenarioStats stats) {
		if (scenario == null || scenario.getAssertions() == null || scenario.getAssertions().isEmpty()) {
			return;
		}
		Context ctx = new Context(invite);
		for (Assertion assertion : scenario.getAssertions()) {
			try {
				if (assertion.expression().evaluate(ctx)) {
					stats.recordAssertionPassed();
				} else if (Assertion.ON_FAIL_WARN.equals(assertion.getOnFail())) {
					stats.recordAssertionWarned();
					sipLogger.warning(invite, "Assertion[" + assertion.getId() + "] warned: " + assertion.getWhen());
				} else {
					stats.recordAssertionFailed();
					sipLogger.warning(invite, "Assertion[" + assertion.getId() + "] failed: " + assertion.getWhen());
				}
			} catch (IllegalArgumentException e) {
				sipLogger.warning(invite,
						"Assertion[" + assertion.getId() + "] is malformed and was skipped: " + e.getMessage());
			}
		}
	}

	private void recordStatusSequence(SipApplicationSession appSession, int status) {
		String sequence = (String) appSession.getAttribute(VAR_STATUS_SEQUENCE);
		sequence = (sequence == null || sequence.isEmpty()) ? String.valueOf(status) : sequence + "," + status;
		appSession.setAttribute(VAR_STATUS_SEQUENCE, sequence);
	}

	/// The engine owns the completed/failed counters (and mirrors them into
	/// the metrics) so the servlet-callback path and this path can't
	/// double-count.
	private void notifyFailed() {
		if (engine != null) {
			engine.onCallFailed();
		}
	}
}
