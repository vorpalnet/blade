package org.vorpal.blade.services.presence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v3.Callflow;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;

/// Where agents live: each in the application session keyed by their address, the same key
/// [PresenceServlet#sessionKey] gives their PUBLISH and SUBSCRIBE, so a request and a routing
/// decision on any engine meet the same copy.
final class Agents {

	private Agents() {
	}

	static SipApplicationSession session(String address, boolean create) {
		return Callflow.getSipUtil().getApplicationSessionByKey(address, create);
	}

	/// The agent held by `app`, or a new one with no publications. Call under the session's lock.
	static Agent of(SipApplicationSession app, String address) {
		Agent agent = (Agent) app.getAttribute(Agent.ATTR);
		return (agent != null) ? agent : new Agent(address);
	}

	/// The agent as `app` holds it now, read without the lock: good enough to choose whom to try,
	/// never to decide. Null when presence has never heard of them.
	static Agent peek(String address) {
		SipApplicationSession app = session(address, false);
		return (app == null || !app.isValid()) ? null : (Agent) app.getAttribute(Agent.ATTR);
	}

	/// Store `agent` and keep its session as long as a publication or subscription runs, an hour at
	/// least, so a long call does not outlive what presence knows about the agent on it. The session
	/// is renewed only when it would otherwise lapse within ten minutes of that, since each renewal
	/// rewrites its index buckets ([Index]). Its index keys ([#SKILL], [#READY]) are brought in line
	/// with what it now serves and is Ready for.
	static void save(SipApplicationSession app, Agent agent, long now) {
		app.setInvalidateWhenReady(false);
		app.setAttribute(Agent.ATTR, agent);
		long minutes = Math.max(60, (agent.lastExpiry() - now) / 60_000 + 1);
		if (app.getExpirationTime() < now + minutes * 60_000 - 10 * 60_000) {
			app.setExpires((int) Math.min(minutes + 10, Integer.MAX_VALUE)); // renewing rewrites the index buckets
		}
		index(app, agent, now);
	}

	/// Index key of every agent serving a skill: `skill:<name>/<bucket>` ([Index]).
	static final String SKILL = "skill:";

	/// Index key of every agent Ready for a skill: `ready:<name>/<bucket>` ([Index]).
	static final String READY = "ready:";

	/// The skills are found through the container's application-session index (SIP Servlet 2.0,
	/// [SipApplicationSession#addIndexKey]): each agent's session carries a key per skill it serves
	/// and per skill it is Ready for, set here under its own lock, and
	/// [javax.servlet.sip.SipSessionsUtil#getSipApplicationSessionIds] finds them from any engine
	/// without opening a session. Nothing else lists who serves what.
	///
	/// A key can outlive its truth, as when a publication expires with no request to say so; the
	/// router reads the agent before it trusts a key, and saving the agent then corrects it.
	private static void index(SipApplicationSession app, Agent agent, long now) {
		Set<String> want = new HashSet<>();
		for (String skill : agent.skills(now)) {
			want.add(Index.key(SKILL, skill, agent.address));
			if (agent.readyFor(skill, now)) {
				want.add(Index.key(READY, skill, agent.address));
			}
		}
		for (String key : new ArrayList<>(app.getIndexKeys())) {
			if ((key.startsWith(SKILL) || key.startsWith(READY)) && !want.remove(key)) {
				app.removeIndexKey(key);
			}
		}
		for (String key : want) {
			app.addIndexKey(key);
		}
		Callflow.getSipLogger().fine("presence: diag index " + agent.address + " " + app.getId() + " keys=" + app.getIndexKeys());
	}

	interface Locked {
		void run(SipApplicationSession app, Agent agent, long now) throws Exception;
	}

	/// Run `task` on `address`'s agent holding their session's lock, then save it. From a thread that
	/// holds no other application session's lock, except a skill's ([Skills] takes skill, then agent).
	static void underLock(String address, Locked task) {
		SipApplicationSession app = session(address, true);
		if (!(app instanceof WlssSipApplicationSession)) {
			return;
		}
		try {
			((WlssSipApplicationSession) app).doAction(new WlssAction() {
				@Override
				public Object run() throws Exception {
					long now = System.currentTimeMillis();
					Agent agent = of(app, address);
					agent.prune(now);
					task.run(app, agent, now);
					save(app, agent, now);
					return null;
				}
			});
		} catch (Exception e) {
			Callflow.getSipLogger().warning("presence: update of " + address + " failed: " + e);
		}
	}

	/// Tell every watcher of `agent` its current state. Call under the agent session's lock.
	static void notifyWatchers(SipApplicationSession app, Agent agent, long now) {
		List<String> gone = new ArrayList<>();
		for (Map.Entry<String, Long> w : agent.watchers.entrySet()) {
			SipSession dialog = app.getSipSession(w.getKey());
			if (dialog == null || !dialog.isValid() || dialog.getState() != SipSession.State.CONFIRMED) {
				gone.add(w.getKey());
				continue;
			}
			notify(dialog, agent, now, (int) Math.max(0, (w.getValue() - now) / 1000));
		}
		for (String id : gone) {
			agent.watchers.remove(id);
		}
	}

	/// One NOTIFY on `dialog`; `expires` 0 ends the subscription.
	static void notify(SipSession dialog, Agent agent, long now, int expires) {
		try {
			SipServletRequest notify = dialog.createRequest("NOTIFY");
			notify.setHeader("Event", "presence");
			notify.setHeader("Subscription-State", (expires > 0) ? "active;expires=" + expires : "terminated;reason=timeout");
			notify.setContent(Pidf.document(agent, now), Pidf.CONTENT_TYPE);
			new Sender().send(notify);
		} catch (Exception e) {
			Callflow.getSipLogger().fine("presence: no NOTIFY for " + agent.address + ": " + e);
		}
	}

	/// Sends through the framework, so the request is logged and on the flight recorder's ladder;
	/// nothing waits on the answer.
	static final class Sender extends Callflow {
		private static final long serialVersionUID = 1L;

		void send(SipServletRequest request) throws Exception {
			sendRequest(request, response -> {
			});
		}

		@Override
		public void process(SipServletRequest request) {
		}
	}
}
