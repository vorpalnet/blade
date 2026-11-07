package org.vorpal.blade.services.presence;

import java.io.IOException;
import java.util.Collections;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v3.Callflow;

/// A call for a skill: an initial INVITE whose Request-URI user is a skill some agent has
/// published (`sip:spanish@…`); any other is refused `404`.
///
/// The caller is told `182 Queued` at once (RFC 3261) and the call joins the skill's line
/// ([Skills]). The 182 is repeated every [PresenceSettings#getQueuedRefreshSeconds] so no proxy's
/// Timer C ends the INVITE while it waits. When [Skills] gives it an agent, [#ring] sends a new
/// INVITE to the agent's address, which the App Router takes on to the registrar and the agent's
/// device; this application stays in the call as a B2BUA, the two dialogs linked, so it sees the
/// answer and the hang-up and knows when the agent is busy and when free again.
///
/// An agent who does not answer within [PresenceSettings#getNoAnswerSeconds], or refuses, is set
/// Not Ready and the call goes back to the front of the line. A call that waits longer than
/// [PresenceSettings#getMaxWaitSeconds] is answered 480; the same check ends a call whose caller
/// vanished without a CANCEL, which would otherwise hold its place in line for good.
public class SkillInvite extends Callflow {
	private static final long serialVersionUID = 1L;

	static final String SKILL = "presence.skill";
	static final String CALLER = "presence.caller";
	static final String AGENT = "presence.agent";
	static final String REFRESH = "presence.refresh";
	static final String SINCE = "presence.since";

	@Override
	public void process(SipServletRequest invite) throws ServletException, IOException {
		String user = ((SipURI) invite.getRequestURI()).getUser();
		PresenceSettings settings = PresenceServlet.settingsManager.getCurrent();
		String skill = (user == null) ? null : user.toLowerCase();
		if (skill == null || !Skills.known(skill)) {
			sendResponse(invite.createResponse(404, "No Such Skill"));
			return;
		}
		SipApplicationSession app = invite.getApplicationSession();
		app.setAttribute(SKILL, skill);
		app.setAttribute(CALLER, invite.getSession().getId());

		sendResponse(invite.createResponse(182));
		long every = Math.max(10, settings.getQueuedRefreshSeconds()) * 1000L;
		String appId = app.getId();
		app.setAttribute(SINCE, Long.valueOf(System.currentTimeMillis()));
		app.setAttribute(REFRESH, startTimer(app, every, every, false, false, timer -> {
			SipApplicationSession held = timer.getApplicationSession();
			SipServletRequest caller = caller(held);
			if (caller == null || held.getAttribute(AGENT) != null || PresenceServlet.settingsManager == null) {
				return;
			}
			int max = PresenceServlet.settingsManager.getCurrent().getMaxWaitSeconds();
			Long since = (Long) held.getAttribute(SINCE);
			if (max > 0 && since != null && System.currentTimeMillis() - since > max * 1000L) {
				settled(held);
				sipLogger.info(caller, "SkillInvite: nobody took the call in " + max + " s");
				sendResponse(caller.createResponse(480, "No Agent Available"));
				return;
			}
			try {
				sendResponse(caller.createResponse(182));
			} catch (Exception e) {
				// The caller's transaction is gone (seen after a redeploy under load): stop waiting.
				settled(held);
				sipLogger.info(caller, "SkillInvite: the caller cannot be answered any more, out of line: " + e);
			}
		}));
		sipLogger.info(invite, "SkillInvite: " + appId + " waits for " + skill);
		Skills.enqueue(app, skill);
	}

	/// Whether `app` is a call still waiting for an agent: its caller is on the line and nobody is
	/// ringing for it.
	static boolean waiting(SipApplicationSession app) {
		return caller(app) != null && app.getAttribute(AGENT) == null;
	}

	/// The caller's INVITE while it is still unanswered, else null.
	static SipServletRequest caller(SipApplicationSession app) {
		String id = (String) app.getAttribute(CALLER);
		SipSession session = (id == null) ? null : app.getSipSession(id);
		if (session == null || !session.isValid()) {
			return null;
		}
		SipServletRequest invite = session.getActiveInvite(UAMode.UAS);
		return (invite == null || invite.isCommitted()) ? null : invite;
	}

	/// Stop repeating 182 and take the call out of its skill's line. Under the call's lock.
	static void settled(SipApplicationSession app) {
		stopTimer(app, (String) app.getAttribute(REFRESH));
		app.removeAttribute(REFRESH);
		Skills.withdraw(app, (String) app.getAttribute(SKILL));
	}

	/// Ring `agent` for the call in `app`, under the call's lock. False when the call cannot be
	/// offered (the caller has gone).
	boolean ring(SipApplicationSession app, String agent) throws Exception {
		SipServletRequest in = caller(app);
		if (in == null || app.getAttribute(AGENT) != null) {
			return false; // the caller left, or another engine's router rang someone first
		}
		String skill = (String) app.getAttribute(SKILL);
		String appId = app.getId();
		app.setAttribute(AGENT, agent);
		Skills.withdraw(app, skill); // ringing, not waiting

		SipServletRequest out = getSipFactory().createRequest(app, INVITE, in.getFrom(), in.getTo());
		out.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, in);
		copyContentAndHeaders(in, out); // links the agent's dialog to the caller's
		out.setRequestURI(getSipFactory().createURI("sip:" + agent));

		long timeout = Math.max(1, PresenceServlet.settingsManager.getCurrent().getNoAnswerSeconds()) * 1000L;
		sendRequestsInParallel(timeout, Collections.singletonList(out), response -> {
			SipServletRequest caller = caller(response.getApplicationSession());
			if (caller == null) {
				return; // the caller left; the hang-up already gave the agent back
			}
			if (successful(response)) {
				settled(response.getApplicationSession());
				SipServletResponse answer = caller.createResponse(response.getStatus());
				copyContentAndHeaders(response, answer); // links the caller to the agent's dialog
				sendResponse(answer, ack -> sendAcknowledgement(ack, response));
				sipLogger.info(caller, "SkillInvite: " + agent + " answered the " + skill + " call");
				Skills.answered(agent, appId);
				return;
			}
			sipLogger.info(caller, "SkillInvite: " + agent + " did not take the " + skill + " call ("
					+ response.getStatus() + "); back to the front of the line");
			response.getApplicationSession().removeAttribute(AGENT);
			Skills.release(agent, appId, true);
			Skills.enqueue(response.getApplicationSession(), skill); // its arrival time puts it first
		}, ringing -> {
			int status = ringing.getStatus();
			SipServletRequest caller = caller(ringing.getApplicationSession());
			if (status > 100 && status < 200 && caller != null) {
				sendResponse(caller.createResponse(180));
			}
		}, failed -> true); // v3's race: it never cancels the branch that answered
		return true;
	}
}
