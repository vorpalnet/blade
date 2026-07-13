package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.services.proxy.balancer.config.BalancerConfig;
import org.vorpal.blade.services.proxy.balancer.config.Endpoint;

/// The OPTIONS health-check cycle. Started once per node from
/// [ProxyBalancerServlet]'s `servletCreated` — every engine pings
/// independently and keeps its own [EndpointHealth] view; there is no shared
/// cluster state.
///
/// Each cycle pings every registry endpoint (unless the endpoint opts out
/// with `ping: false`), then reschedules itself. A config publish calls
/// [#reschedule] ([ProxyBalancerSettingsManager]), which supersedes the
/// pending timer so a changed `pingInterval` or `pingEnabled` takes effect
/// IMMEDIATELY — not after the old interval runs out. The generation counter
/// makes a superseded chain die quietly even if a publish races a firing
/// timer, so there is never more than one chain per node.
///
/// Verdicts: ANY final response except 408/503 proves the box is alive —
/// a 405 Method Not Allowed is an endpoint that dislikes OPTIONS, not a dead
/// endpoint. The container-generated 408 (nothing answered) and 503
/// (overload) mark it down.
public class OptionsPingCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Applied when pingInterval is missing or nonsensical.
	static final int DEFAULT_PING_INTERVAL_SECONDS = 60;

	// Per-node chain state (static per WAR classloader, like the strategy
	// rotation counters): the timer app session, the pending timer id, and
	// the chain generation. A reschedule bumps the generation; callbacks
	// from an older generation return without acting.
	private static volatile SipApplicationSession timerSession;
	private static volatile String timerId;
	private static final AtomicInteger generation = new AtomicInteger();

	/// Begin the ping cycle. The timer lives on its own never-expiring app
	/// session; each firing pings and schedules the next.
	public void start() throws ServletException, IOException {
		SipApplicationSession appSession = sipFactory.createApplicationSession();
		appSession.setInvalidateWhenReady(false);
		appSession.setExpires(0); // never expire; this session carries the timer
		timerSession = appSession;
		scheduleNext(appSession, generation.get());
	}

	/// Called on every config publish: supersede the pending timer and
	/// schedule fresh with the NEW interval. No-op before [#start] has run
	/// (the initial config load happens inside the SettingsManager
	/// constructor, before servletCreated starts the cycle).
	public static void reschedule() {
		SipApplicationSession appSession = timerSession;
		if (appSession == null) {
			return;
		}
		int gen = generation.incrementAndGet();
		try {
			OptionsPingCallflow callflow = new OptionsPingCallflow();
			String pending = timerId;
			if (pending != null) {
				callflow.stopTimer(appSession, pending);
			}
			callflow.scheduleNext(appSession, gen);
		} catch (Exception e) {
			sipLogger.warning("OptionsPingCallflow.reschedule - " + e.getMessage());
		}
	}

	private void scheduleNext(SipApplicationSession appSession, int gen) throws ServletException, IOException {
		if (gen != generation.get()) {
			return; // superseded by a reschedule; the new chain owns the timer
		}

		Integer interval = ProxyBalancerServlet.settingsManager.getCurrent().getHealth().getPingInterval();
		int seconds = (interval != null && interval > 0) ? interval : DEFAULT_PING_INTERVAL_SECONDS;

		timerId = startTimer(appSession, seconds * 1000L, false, (timer) -> {
			if (gen != generation.get()) {
				return;
			}
			try {
				pingAll();
			} catch (Exception e) {
				// the cycle must survive anything a ping throws
				sipLogger.warning("OptionsPingCallflow.pingAll - " + e.getMessage());
			}
			scheduleNext(appSession, gen);
		});
	}

	private void pingAll() throws ServletException, IOException {
		BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();

		if (Boolean.FALSE.equals(config.getHealth().getPingEnabled())) {
			return; // toggled off; keep the cycle alive so it can be re-enabled
		}

		for (Entry<String, Endpoint> entry : config.getEndpoints().entrySet()) {
			String name = entry.getKey();
			Endpoint endpoint = entry.getValue();

			if (Boolean.FALSE.equals(endpoint.getPing())) {
				continue;
			}
			EndpointHealth health = config.endpointHealth.get(name);
			if (health == null) {
				continue;
			}

			try {
				SipServletRequest options = sipFactory.createRequest(sipFactory.createApplicationSession(),
						OPTIONS, ProxyBalancerServlet.servletContextName, endpoint.getUri());

				final long pingStart = System.nanoTime();
				sendRequest(options, (response) -> {
					int rttMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - pingStart) / 1_000_000L);
					int status = response.getStatus();
					if (status == 408) {
						// nothing answered — the 408 is locally generated, so
						// the elapsed time is the timeout, not a round trip
						health.markDown("OPTIONS 408", null, "ping", -1);
					} else if (status == 503) {
						// the endpoint said it's overloaded (it DID answer, so
						// the round trip is real)
						health.markDown("OPTIONS 503", null, "ping", rttMs);
					} else {
						// any other final response proves the box is alive —
						// a 405 is an endpoint that dislikes OPTIONS, not a dead one
						health.markUp("OPTIONS " + status, "ping", rttMs);
					}

					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(response, "OptionsPingCallflow.pingAll - " + name + " -> "
								+ health.getStatus() + " (" + health.getNote() + ")");
					}

					SipApplicationSession pingSession = response.getApplicationSession();
					if (pingSession != null && pingSession.isValid()) {
						pingSession.invalidate();
					}
				});
			} catch (Exception badEndpoint) {
				// one unpingable endpoint (e.g. a malformed URI) must not
				// stop the rest of the cycle — and the dashboard should say so
				health.markDown("unpingable: " + badEndpoint.getMessage(), null, "ping", -1);
			}

		}
	}

	/// Not request-driven; this callflow is started by the servlet, not
	/// dispatched. Present only to satisfy the Callflow contract.
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sipLogger.warning(request, "OptionsPingCallflow is not dispatchable; use start()");
	}

}
