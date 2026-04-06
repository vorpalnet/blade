package org.vorpal.blade.test.uac;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/// Per-call lifecycle handler for load-generated calls.
///
/// Sends the INVITE, ACKs on success, schedules auto-BYE after the
/// configured duration, and notifies the LoadGenerator on completion.
public class LoadCallflow extends ClientCallflow {

	private static final long serialVersionUID = 1L;
	private transient LoadGenerator generator;

	public LoadCallflow(LoadGenerator generator) {
		this.generator = generator;
	}

	/// Sends the INVITE and manages the full call lifecycle.
	public void makeCall(SipServletRequest invite) throws ServletException, IOException {
		int durationSec = 30;
		Object attr = invite.getApplicationSession().getAttribute("callDuration");
		if (attr instanceof Integer) {
			durationSec = (Integer) attr;
		}

		final int finalDuration = durationSec;

		sendRequest(invite, (response) -> {
			try {
				if (successful(response)) {
					sendRequest(response.createAck());

					// Schedule auto-BYE after call duration
					if (finalDuration > 0) {
						scheduleTimer(invite.getApplicationSession(), finalDuration, (timer) -> {
							SipSession session = invite.getSession();
							if (session != null && session.isValid()
									&& session.getState() != SipSession.State.TERMINATED) {
								try {
									sendRequest(session.createRequest("BYE"));
								} catch (Exception e) {
									sipLogger.logStackTrace(e);
									generator.onCallFailed();
								}
							}
						});
					}
				}

				if (!provisional(response)) {
					if (failure(response)) {
						generator.onCallFailed();
					}
					// Completed calls are handled by UserAgentClientServlet.callCompleted()
				}
			} catch (Exception e) {
				sipLogger.logStackTrace(e);
				generator.onCallFailed();
			}
		});
	}

}
