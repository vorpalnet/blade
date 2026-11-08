package org.vorpal.blade.services.presence;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.configuration.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;

/// Agent presence and skill routing.
///
/// - **PUBLISH** ([PublishCallflow]): an agent's device says Ready (`open`) or Not Ready (`closed`).
/// - **SUBSCRIBE** ([SubscribeCallflow]): anyone may watch an agent and is sent NOTIFY on every
///   change.
/// - **INVITE** to a skill's address ([SkillInvite]): the call waits with 182 Queued until the
///   longest-idle Ready agent in the skill is free, then rings them ([Skills]).
///
/// PUBLISH and SUBSCRIBE are keyed by the presentity (the To address), so all of one agent's state
/// is in one application session. An INVITE has no key: each call has its own session, and a skill's
/// calls never queue on one lock. In-dialog requests of a call are the B2BUA's, inherited.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class PresenceServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<PresenceSettings> settingsManager;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest request) {
		switch (request.getMethod()) {
		case "PUBLISH":
		case "SUBSCRIBE":
			return getAccountName(request.getTo());
		default:
			return null;
		}
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<PresenceSettings>(event) {
			@Override
			protected PresenceSettings sample() {
				return new PresenceSettingsSample();
			}

			@Override
			protected void refreshed(PresenceSettings config) {
			}
		};
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		Skills.shutdown();
		if (settingsManager != null) {
			settingsManager.unregister();
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		switch (request.getMethod()) {
		case "PUBLISH":
			return new PublishCallflow();
		case "SUBSCRIBE":
			return new SubscribeCallflow();
		case "INVITE":
			if (request.isInitial()) {
				return new SkillInvite();
			}
			return super.chooseCallflow(request);
		case "CANCEL":
			callerLeft(request.getApplicationSession());
			return super.chooseCallflow(request);
		default:
			return super.chooseCallflow(request);
		}
	}

	/// A caller hung up before an agent answered: off the skill's line, and no more 182s. An agent
	/// already ringing is given back by [#callAbandoned], when the B2BUA cancels their leg.
	private static void callerLeft(SipApplicationSession app) {
		String skill = (String) app.getAttribute(SkillInvite.SKILL);
		if (skill != null) {
			SkillInvite.settled(app);
		}
	}

	/// The agent and the call this B2BUA message belongs to are done with each other.
	private static void agentFree(SipServletRequest outbound) {
		SipApplicationSession app = outbound.getApplicationSession();
		String agent = (String) app.getAttribute(SkillInvite.AGENT);
		if (agent != null) {
			app.removeAttribute(SkillInvite.AGENT);
			Skills.release(agent, app.getId(), false);
		}
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		agentFree(outboundRequest);
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		agentFree(outboundRequest);
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
	}
}
