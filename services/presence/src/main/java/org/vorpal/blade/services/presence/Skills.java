package org.vorpal.blade.services.presence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v3.Callflow;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;

/// Matches waiting calls with Ready agents, skill by skill.
///
/// ## Two lines, and no session of their own
///
/// Both sides of a skill are found through the container's application-session index (SIP Servlet
/// 2.0, [SipApplicationSession#addIndexKey]), each entry kept in its own session under its own lock:
///
/// - a waiting call's session carries [#WAITING]`<skill>`, set by [SkillInvite] while it waits;
///   the line's order is each call's arrival time ([SkillInvite#SINCE]), so a call an agent left
///   unanswered is back at the front without being moved;
/// - an agent's session carries [Agents#SKILL]`<skill>` for each skill they serve and
///   [Agents#READY]`<skill>` for each they are Ready for ([Agents#save]).
///
/// Each key is split into buckets ([Index]), since the container's cost of a key grows with the
/// number of sessions on it.
///
/// So no session is shared by a skill's calls or agents. A skill has no lock that every call and
/// every agent must pass through, and none that, left held, would stop the skill.
///
/// The index finds who is Ready but not in what order. Rather than read every Ready agent for the
/// one idle longest, the router reads a few at random ([#SAMPLE]) and offers the call to the
/// longest idle among them: one routing decision costs a handful of reads however large the skill.
///
/// A match is tried whenever either side changes: a call arrives or comes back unanswered, or an
/// agent turns Ready (published `open`, or their call ended). The agent is claimed under their own
/// lock (Ready to ringing), so an agent in two skills, or wanted by two engines, is claimed once;
/// the call is then rung under its own lock, and a call another engine reached first gives the
/// agent back.
///
/// ## One thread, no nesting
///
/// Everything here runs on one thread per engine, started by whoever saw the change, holding no
/// application session's lock of its own, and it holds one session's lock at a time.
final class Skills {

	/// Index key of every call waiting for a skill: `waiting:<name>/<bucket>` ([Index]).
	static final String WAITING = "waiting:";

	/// How many Ready agents one routing decision reads.
	static final int SAMPLE = 8;

	private static final ExecutorService ROUTER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "presence-router");
		t.setDaemon(true);
		return t;
	});

	private Skills() {
	}

	static void shutdown() {
		ROUTER.shutdownNow();
	}

	/// Run `step` on the router thread. Not `submit`: its Future would keep a failure where nobody
	/// looks, and the step would vanish without a word.
	private static void route(Runnable step) {
		ROUTER.execute(() -> {
			try {
				step.run();
			} catch (RuntimeException e) {
				Callflow.getSipLogger().warning("presence: routing step failed: " + e);
				Callflow.getSipLogger().logStackTrace(e);
			}
		});
	}

	// ---- the changes ---------------------------------------------------------------------------

	/// The call in `app` waits for `skill`. Under the call's lock: its index key is set now, and the
	/// router looks for an agent.
	///
	/// The key reaches the index only when the session is put back, after the caller's request (or
	/// response) is done with it, so the router first opens the call's session, which waits for
	/// that; a router that went straight to the index would not yet see the call.
	static void enqueue(SipApplicationSession app, String skill) {
		app.addIndexKey(Index.key(WAITING, skill, app.getId()));
		String call = app.getId();
		route(() -> {
			Callflow.getSipUtil().getApplicationSessionById(call);
			match(skill);
		});
	}

	/// The call in `app` no longer waits for `skill`. Under the call's lock.
	static void withdraw(SipApplicationSession app, String skill) {
		if (skill != null && app.isValid()) {
			app.removeIndexKey(Index.key(WAITING, skill, app.getId()));
		}
	}

	/// `agent` may have turned Ready: try every skill they serve.
	static void wake(String agent) {
		route(() -> {
			for (String skill : skillsOf(agent)) {
				match(skill);
			}
		});
	}

	/// Whether some agent serves `skill`: a call for any other is refused at once. Reads the index
	/// only, so a request thread never waits on another session's lock.
	static boolean known(String skill) {
		return Index.any(Agents.SKILL, skill);
	}

	private static Set<String> skillsOf(String agent) {
		Agent seen = Agents.peek(agent);
		return (seen == null) ? Collections.emptySet() : seen.skills(System.currentTimeMillis());
	}

	/// The agent answered `call`.
	static void answered(String agent, String call) {
		route(() -> Agents.underLock(agent, (app, a, now) -> {
			if (call.equals(a.reservedFor)) {
				a.reservedFor = null;
			}
			a.busyWith = call;
			Agents.notifyWatchers(app, a, now);
		}));
	}

	/// `call` no longer needs `agent`: it ended, or the caller left while it rang. `hold` sets the
	/// agent Not Ready, for a call they did not answer.
	static void release(String agent, String call, boolean hold) {
		route(() -> {
			Set<String> serves = new LinkedHashSet<>();
			Agents.underLock(agent, (app, a, now) -> {
				serves.addAll(a.skills(now));
				boolean wasBusy = call.equals(a.busyWith);
				if (wasBusy) {
					a.busyWith = null;
				}
				if (call.equals(a.reservedFor)) {
					a.reservedFor = null;
				}
				if (hold) {
					a.held = true;
				}
				if (a.ready(now)) {
					a.idleSince = now;
				}
				Agents.notifyWatchers(app, a, now);
				Callflow.getSipLogger().info("presence: " + agent + " is " + a.status(now)
						+ (hold ? " (did not answer)" : wasBusy ? " (call ended)" : ""));
			});
			for (String skill : serves) {
				match(skill);
			}
		});
	}

	// ---- the match -------------------------------------------------------------------------------

	/// Offer `skill`'s waiting calls, oldest first, to Ready agents until one side runs out.
	private static void match(String skill) {
		if (!Index.any(Agents.READY, skill)) {
			return; // nobody to offer to: the waiting calls are not even read
		}
		List<Object[]> line = new ArrayList<>();
		for (String id : Index.all(WAITING, skill)) {
			SipApplicationSession app = Callflow.getSipUtil().getApplicationSessionById(id);
			Long since = (app == null || !app.isValid()) ? null : (Long) app.getAttribute(SkillInvite.SINCE);
			if (since != null && SkillInvite.waiting(app)) {
				line.add(new Object[] { since, id });
			}
		}
		line.sort(Comparator.comparingLong(entry -> (Long) entry[0]));
		for (Object[] entry : line) {
			String call = (String) entry[1];
			String agent = claim(skill, call);
			if (agent == null) {
				return;
			}
			offer(skill, call, agent);
		}
	}

	/// An agent Ready for `skill`, now ringing for `call`: the longest idle of [#SAMPLE] read at
	/// random from the index, a bucket at a time ([Index]); null when the index names nobody Ready.
	/// An agent whose key turns out stale is saved, which corrects it, and the next few are read.
	private static String claim(String skill, String call) {
		List<String> ids = new ArrayList<>();
		for (int shard : Index.shuffled()) {
			if (ids.size() >= SAMPLE) {
				break;
			}
			ids.addAll(Index.bucket(Agents.READY, skill, shard));
		}
		Callflow.getSipLogger().info("presence: diag claim " + skill + " for " + call + ": sampled ready=" + ids.size());
		String found = claimFrom(skill, call, ids);
		if (found != null || ids.isEmpty()) {
			return found;
		}
		return claimFrom(skill, call, Index.all(Agents.READY, skill)); // every sampled key was stale
	}

	private static String claimFrom(String skill, String call, List<String> ids) {
		Collections.shuffle(ids);
		int gone = 0, bare = 0, busy = 0, read = 0;
		long started = System.nanoTime();
		try {
		for (int from = 0; from < ids.size(); from += SAMPLE) {
			long now = System.currentTimeMillis();
			List<Agent> candidates = new ArrayList<>();
			for (String id : ids.subList(from, Math.min(ids.size(), from + SAMPLE))) {
				read++;
				SipApplicationSession app = Callflow.getSipUtil().getApplicationSessionById(id);
				Agent seen = (app == null || !app.isValid()) ? null : (Agent) app.getAttribute(Agent.ATTR);
				if (seen == null) {
					if (app == null || !app.isValid()) {
						gone++;
					} else {
						bare++;
					}
					continue;
				}
				if (seen.readyFor(skill, now)) {
					candidates.add(seen);
				} else {
					busy++;
					Agents.underLock(seen.address, (held, a, t) -> {
					}); // re-index: the key said Ready, the agent is not
				}
			}
			candidates.sort(Comparator.comparingLong(a -> a.idleSince));
			for (Agent candidate : candidates) {
				String[] won = new String[1];
				Agents.underLock(candidate.address, (held, a, t) -> {
					if (a.readyFor(skill, t)) {
						a.reservedFor = call;
						won[0] = a.address;
						Agents.notifyWatchers(held, a, t);
					}
				});
				if (won[0] != null) {
					return won[0];
				}
			}
		}
		return null;
		} finally {
			Callflow.getSipLogger().info("presence: diag claimFrom " + skill + " ids=" + ids.size() + " read=" + read
					+ " gone=" + gone + " noAgent=" + bare + " notReady=" + busy + " ms="
					+ (System.nanoTime() - started) / 1_000_000);
		}
	}

	/// Ring `agent` for `call`, under the call's lock. A call that can no longer be offered (its
	/// caller left, or another engine's router rang someone first) gives the agent back.
	private static void offer(String skill, String call, String agent) {
		SipApplicationSession app = Callflow.getSipUtil().getApplicationSessionById(call);
		boolean[] rang = new boolean[1];
		underLock(app, held -> rang[0] = new SkillInvite().ring(held, agent));
		if (!rang[0]) {
			release(agent, call, false);
		} else {
			Callflow.getSipLogger().info("presence: " + skill + " call " + call + " offered to " + agent);
		}
	}

	interface Locked extends Serializable {
		void run(SipApplicationSession app) throws Exception;
	}

	private static void underLock(SipApplicationSession app, Locked task) {
		if (!(app instanceof WlssSipApplicationSession) || !app.isValid()) {
			Callflow.getSipLogger().warning("presence: diag routing step skipped: session "
					+ ((app == null) ? "null" : app.getId() + " valid=" + app.isValid()));
			return;
		}
		try {
			((WlssSipApplicationSession) app).doAction(new WlssAction() {
				@Override
				public Object run() throws Exception {
					task.run(app);
					return null;
				}
			});
		} catch (Exception e) {
			Callflow.getSipLogger().warning("presence: routing step failed on " + app.getId() + ": " + e);
		}
	}
}
