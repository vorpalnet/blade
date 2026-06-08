package org.vorpal.blade.framework.v3.tester;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Endpoint-mode responder driven by a [Scenario]'s [ResponseScript]: plays
/// the `send` steps in order (each with its own delay and optional custom
/// reason phrase), answers 2xx steps with a blackhole/mute SDP derived from
/// the offer (unless the step says `sdp: none`), then optionally runs a
/// REFER transfer and/or an auto-BYE teardown.
///
/// Generalizes the old test-uas `TestInvite` (status/delay) and `TestRefer`
/// (transfer + NOTIFY handshake) callflows — those behaviors are now just
/// particular response scripts, which the URI-parameter shorthands
/// (`status=`, `delay=`, `refer=`) synthesize at runtime.
public class ScriptedAnswer extends Callflow {
	private static final long serialVersionUID = 1L;

	private final Scenario scenario;
	private final String scenarioName;
	private transient TesterMetrics metrics;

	public ScriptedAnswer(Scenario scenario, String scenarioName, TesterMetrics metrics) {
		this.scenario = scenario;
		this.scenarioName = scenarioName;
		this.metrics = metrics;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		ResponseScript script = scenario.getResponseScript();
		List<ResponseStep> steps = (script != null && script.getSend() != null && !script.getSend().isEmpty())
				? script.getSend()
				: Collections.singletonList(new ResponseStep(200));
		playStep(request, script, steps, 0);
	}

	private void playStep(SipServletRequest request, ResponseScript script, List<ResponseStep> steps, int index)
			throws ServletException, IOException {
		if (index >= steps.size()) {
			return;
		}
		long delayMs = ResponseScript.parseMillis(steps.get(index).getDelay());
		if (delayMs > 0) {
			startTimer(request.getApplicationSession(), delayMs, false, (timer) -> {
				sendStep(request, script, steps, index);
			});
		} else {
			sendStep(request, script, steps, index);
		}
	}

	private void sendStep(SipServletRequest request, ResponseScript script, List<ResponseStep> steps, int index)
			throws ServletException, IOException {
		ResponseStep step = steps.get(index);
		int status = step.getStatus();

		SipServletResponse response = (step.getReasonPhrase() != null)
				? request.createResponse(status, step.getReasonPhrase())
				: request.createResponse(status);

		boolean answered = status >= 200 && status < 300;
		if (answered && !ResponseStep.SDP_NONE.equals(step.getSdp())) {
			try {
				hold(request, response); // blackhole/mute answer derived from the offer
			} catch (MessagingException e) {
				throw new IOException("ScriptedAnswer: failed to build hold answer from offer", e);
			}
		}

		if (status < 200) {
			sendResponse(response);
			playStep(request, script, steps, index + 1);
			return;
		}

		// Final response — anything scripted after it can never be sent.
		if (index + 1 < steps.size()) {
			sipLogger.warning(request, "ScriptedAnswer[" + scenarioName + "]: ignoring " + (steps.size() - index - 1)
					+ " step(s) after final status " + status);
		}
		if (metrics != null) {
			metrics.scenario(scenarioName).recordAnswered(status);
		}

		if (!answered) {
			sendResponse(response);
			return;
		}

		sendResponse(response, (ack) -> {
			if (script != null && script.getRefer() != null) {
				doRefer(request, ack.getSession(), script);
			}
			scheduleAutoBye(request, script);
		});
	}

	/// Answers-then-transfers: send a REFER whose `Refer-To` is the script's
	/// address, drive the implicit-subscription NOTIFY handshake (100 Trying,
	/// then final), and on a successful outcome (a `200` in the final
	/// NOTIFY's `message/sipfrag` body) tear down the original dialog.
	private void doRefer(SipServletRequest request, SipSession session, ResponseScript script)
			throws ServletException, IOException {
		Address referTo = getSipFactory().createAddress(script.getRefer());
		if (script.getReferStatus() != null) {
			referTo.getURI().setParameter("status", script.getReferStatus());
		}

		SipServletRequest refer = session.createRequest(REFER);
		refer.setAddressHeader("Refer-To", referTo);
		refer.setAddressHeader("Referred-By", request.getTo());
		sendRequest(refer);

		// expect NOTIFY: SIP/2.0 100 Trying
		expectRequest(refer.getSession(), NOTIFY, (notify) -> {
			sendResponse(notify.createResponse(200));

			// expect NOTIFY: final transfer outcome
			expectRequest(refer.getSession(), NOTIFY, (notify2) -> {
				sendResponse(notify2.createResponse(200));

				Object content = notify2.getContent();
				String sipFrag = (content instanceof byte[]) ? new String((byte[]) content) : String.valueOf(content);
				if (sipFrag.contains("200")) {
					sendRequest(refer.getSession().createRequest(BYE), (byeResponse) -> {
						// do nothing
					});
				}
			});
		});
	}

	private void scheduleAutoBye(SipServletRequest request, ResponseScript script) {
		long byeMs = (script != null) ? ResponseScript.parseMillis(script.getAutoByeAfter()) : 0;
		if (byeMs <= 0) {
			return;
		}
		startTimer(request.getApplicationSession(), byeMs, false, (timer) -> {
			SipSession session = request.getSession();
			if (session != null && session.isValid() && session.getState() != SipSession.State.TERMINATED) {
				sendRequest(session.createRequest(BYE));
			}
		});
	}
}
