package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.services.proxy.balancer.config.BalancerConfig;
import org.vorpal.blade.services.proxy.balancer.config.Endpoint;
import org.vorpal.blade.services.proxy.balancer.config.Tier;

/// Forwards an initial INVITE through the tiers of the plan selected for the
/// request URI's host (exact key, longest `*.suffix` wildcard, then `"*"`) —
/// a forking B2BUA over the fan-out primitives. Tier order is priority
/// order: for least-cost routing, list the cheapest tier first.
///
/// Each tier resolves its endpoint NAMES through the config registry,
/// dropping drained (`enabled: false`) and unhealthy endpoints, then orders
/// the survivors by strategy: `parallel` races them all; `serial` hunts in
/// listed order; `random` shuffles; `roundrobin` rotates a per-node offset;
/// `weighted` hunts in smooth weighted round-robin order (deterministic,
/// same per-node counter).
///
/// Failover is response-classified ([#shouldFailover]): route-level
/// failures (408/timeout, 480, 404, 5xx, 3xx) escalate to the next tier;
/// user-state and auth responses (486 busy, 401/407, 487 canceled, all 6xx
/// — RFC 3261 §16.7 stops branching on a global failure) are relayed to the
/// caller without touching a more expensive tier. Nothing routable answers
/// 503.
///
/// The per-leg observer relays real 18x ringing upstream and passively marks
/// endpoint health (2xx → up; 503 → down, honoring Retry-After, else the
/// configured defaultBackoff). With `session:passthru` set, the winning leg
/// drops out of the dialog after setup.
public class InviteCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Leg-request attribute carrying the endpoint's registry NAME, so the
	/// observer can mark health without reverse-mapping URIs.
	static final String ENDPOINT_NAME_ATTR = "balancer.endpointName";

	/// Round-robin offsets, keyed by "planKey#tierIndex". Deliberately
	/// per-node (static, per WAR classloader): every engine rotates
	/// independently, which still balances in aggregate and needs no shared
	/// cluster state.
	private static final ConcurrentHashMap<String, AtomicInteger> rotation = new ConcurrentHashMap<>();

	@Override
	public void process(SipServletRequest aliceRequest) throws ServletException, IOException {

		BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();

		URI requestURI = aliceRequest.getRequestURI();
		String host = (requestURI instanceof SipURI) ? ((SipURI) requestURI).getHost() : null;
		String planKey = config.findPlanKey(host);
		List<Tier> plan = (planKey != null) ? config.getPlans().get(planKey) : null;

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(aliceRequest, "InviteCallflow.process - host=" + host + ", planKey=" + planKey);
		}

		if (plan == null || plan.isEmpty()) {
			sendResponse(aliceRequest.createResponse(404));
			return;
		}

		// Ring the caller immediately; real 18x from the legs is also relayed
		// (via the per-leg observer), but until a leg responds the caller's UA
		// generates local ringback from this one.
		sendResponse(aliceRequest.createResponse(180));

		forwardTier(aliceRequest, new LinkedList<>(plan), planKey, 0); // copy; tiers are consumed
	}

	/// Forwards to the plan's next tier, recursing on failure until a tier
	/// succeeds or the plan is exhausted.
	private void forwardTier(SipServletRequest aliceRequest, LinkedList<Tier> plan, String planKey, int tierIndex)
			throws ServletException, IOException {

		Tier tier = plan.remove(0);
		BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();

		Map<String, Endpoint> routable = routableEndpoints(tier, config, aliceRequest);
		List<Map.Entry<String, Endpoint>> ordered = orderByStrategy(tier, routable, aliceRequest, planKey, tierIndex);

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(aliceRequest, "InviteCallflow.forwardTier - tier=" + tierIndex + " '" + tier.getName()
					+ "', strategy=" + tier.getStrategy() + ", routable=" + routable.keySet() + " of "
					+ tier.getEndpoints());
		}

		SipApplicationSession appSession = aliceRequest.getApplicationSession();

		List<SipServletRequest> bobRequests = new LinkedList<>();
		for (Map.Entry<String, Endpoint> entry : ordered) {
			try {
				SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, aliceRequest.getFrom(),
						aliceRequest.getTo());
				bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
				copyContentAndHeaders(aliceRequest, bobRequest); // links each leg back to alice
				bobRequest.setRequestURI(sipFactory.createURI(entry.getValue().getUri()));
				bobRequest.setAttribute(ENDPOINT_NAME_ATTR, entry.getKey());
				bobRequests.add(bobRequest);
			} catch (Exception badEndpoint) {
				sipLogger.warning(aliceRequest, "InviteCallflow.forwardTier - skipping endpoint '" + entry.getKey()
						+ "': " + badEndpoint.getMessage());
			}
		}

		if (bobRequests.isEmpty()) {
			// nothing routable in this tier; move on, or give up if it was the last
			if (!plan.isEmpty()) {
				forwardTier(aliceRequest, plan, planKey, tierIndex + 1);
			} else {
				sendResponse(aliceRequest.createResponse(503));
			}
			return;
		}

		// Every response from every leg: passive health marking, and relay of
		// real ringing (180/183) upstream. 100 Trying is hop-by-hop.
		Callback<SipServletResponse> legObserver = (legResponse) -> {
			trackHealth(legResponse);
			int status = legResponse.getStatus();
			if (status > 100 && status < 200 && !aliceRequest.isCommitted()) {
				SipServletResponse aliceRinging = aliceRequest.createResponse(status);
				copyContentAndHeaders(legResponse, aliceRinging);
				sendResponse(aliceRinging);
			}
		};

		Callback<SipServletResponse> tierComplete = (bobResponse) -> {

			// The isCommitted() guard matters: a caller who CANCELed has
			// already been answered 487 by Terminate — the losing leg's 487
			// (or any late failure) must not march up the cost ladder on
			// behalf of a caller who is gone.
			if (!successful(bobResponse) && !plan.isEmpty() && shouldFailover(bobResponse)
					&& !aliceRequest.isCommitted()) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(bobResponse, "InviteCallflow.forwardTier - tier " + tierIndex
							+ " failed, status=" + bobResponse.getStatus() + ", tiers remaining=" + plan.size());
				}
				forwardTier(aliceRequest, plan, planKey, tierIndex + 1);
				return;
			}

			if (!aliceRequest.isCommitted()) {

				SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
				copyContentAndHeaders(bobResponse, aliceResponse); // links alice to the winning leg

				if (successful(bobResponse)) {
					sendResponse(aliceResponse, (aliceAck) -> {
						sendRequest(copyContentAndHeaders(aliceAck, bobResponse.createAck()));
					});
				} else {
					sendResponse(aliceResponse);
				}

			}

		};

		Integer timeout = tier.getTimeout();
		long timeoutMillis = ((timeout != null && timeout > 0) ? timeout : 180) * 1000L;

		if (tier.getStrategy() == Tier.Strategy.parallel) {
			sendRequestsInParallel(timeoutMillis, bobRequests, tierComplete, legObserver);
		} else {
			// all hunt strategies: the ordering above IS the strategy
			sendRequestsInSerial(timeoutMillis, bobRequests, tierComplete, legObserver);
		}

	}

	/// The tier's endpoints this node will offer the call: resolved through
	/// the registry, minus dangling references, drained endpoints, and ones
	/// health says are down. Insertion order preserves the tier's listing.
	private Map<String, Endpoint> routableEndpoints(Tier tier, BalancerConfig config, SipServletRequest request) {
		long now = System.currentTimeMillis();

		Map<String, Endpoint> routable = new LinkedHashMap<>();
		for (String name : tier.getEndpoints()) {
			Endpoint endpoint = config.getEndpoints().get(name);
			if (endpoint == null) {
				sipLogger.warning(request, "InviteCallflow - unknown endpoint '" + name + "' in tier '"
						+ tier.getName() + "'");
				continue;
			}
			if (endpoint.isDrained()) {
				continue;
			}
			EndpointHealth health = config.endpointHealth.get(name);
			if (health != null && !health.isRoutable(now)) {
				continue;
			}
			routable.put(name, endpoint);
		}
		return routable;
	}

	/// Orders the endpoints per the tier's strategy. Hunt strategies reduce
	/// to "order the list, then hunt serially"; parallel keeps listing order
	/// (irrelevant in a race).
	private List<Map.Entry<String, Endpoint>> orderByStrategy(Tier tier, Map<String, Endpoint> routable,
			SipServletRequest aliceRequest, String planKey, int tierIndex) {

		List<Map.Entry<String, Endpoint>> ordered = new ArrayList<>(routable.entrySet());
		if (ordered.size() <= 1) {
			return ordered;
		}

		switch (tier.getStrategy()) {
		case random:
			Collections.shuffle(ordered, ThreadLocalRandom.current());
			return ordered;

		case roundrobin: {
			int offset = Math.floorMod(nextCount(planKey, tierIndex), ordered.size());
			Collections.rotate(ordered, -offset);
			return ordered;
		}

		case weighted:
			return weightedOrder(ordered, nextCount(planKey, tierIndex));

		case parallel:
		case serial:
		default:
			return ordered;
		}
	}

	private static int nextCount(String planKey, int tierIndex) {
		return rotation.computeIfAbsent(planKey + "#" + tierIndex, (k) -> new AtomicInteger()).getAndIncrement();
	}

	/// Smooth weighted round-robin (deterministic — same bounded short-window
	/// skew as roundrobin, no randomness): build the smooth sequence over
	/// `totalWeight` slots (weight 2 vs 1 yields A A B, not A A B clumped),
	/// take the per-node counter's slot as the first attempt, and let the
	/// rest of the hunt order follow the sequence, first occurrences winning.
	/// Over any `totalWeight` consecutive calls on one node, each endpoint
	/// gets exactly `weight` first attempts.
	private static List<Map.Entry<String, Endpoint>> weightedOrder(List<Map.Entry<String, Endpoint>> pool,
			int count) {
		int size = pool.size();
		int totalWeight = 0;
		for (Map.Entry<String, Endpoint> e : pool) {
			totalWeight += weightOf(e.getValue());
		}

		// classic smooth WRR: each slot, everyone gains its weight; the
		// richest is picked and pays back the total
		int[] current = new int[size];
		int[] sequence = new int[totalWeight];
		for (int slot = 0; slot < totalWeight; slot++) {
			int best = 0;
			for (int i = 0; i < size; i++) {
				current[i] += weightOf(pool.get(i).getValue());
				if (current[i] > current[best]) {
					best = i;
				}
			}
			current[best] -= totalWeight;
			sequence[slot] = best;
		}

		// hunt order: walk the sequence from this call's slot; first
		// occurrence of each endpoint fixes its place in the order
		int start = Math.floorMod(count, totalWeight);
		List<Map.Entry<String, Endpoint>> orderedResult = new ArrayList<>(size);
		boolean[] taken = new boolean[size];
		for (int i = 0; i < totalWeight && orderedResult.size() < size; i++) {
			int idx = sequence[(start + i) % totalWeight];
			if (!taken[idx]) {
				taken[idx] = true;
				orderedResult.add(pool.get(idx));
			}
		}
		return orderedResult;
	}

	private static int weightOf(Endpoint endpoint) {
		Integer weight = endpoint.getWeight();
		return (weight != null && weight > 0) ? weight : 1;
	}

	/// Should this tier-final response escalate to the next (possibly more
	/// expensive) tier? Route-level failures do. User-state and auth
	/// responses do not: 486 is the called party busy, not a broken route;
	/// 401/407 must reach the caller for auth to complete; 487 means the
	/// caller canceled; and 6xx is a global failure — RFC 3261 §16.7 stops
	/// trying other branches on 6xx.
	private static boolean shouldFailover(SipServletResponse response) {
		int status = response.getStatus();
		if (status >= 600) {
			return false;
		}
		switch (status) {
		case 401:
		case 407:
		case 486:
		case 487:
			return false;
		default:
			// 3xx, 404, 408, 480, 5xx, ... — try the next tier
			return true;
		}
	}

	/// Passive health marking from a live leg response: 2xx proves the
	/// endpoint alive; 503 is the overload signal — Retry-After honored,
	/// else the configured defaultBackoff. User responses (486 busy, 603
	/// decline, other 4xx) say nothing about the endpoint and are ignored —
	/// the OPTIONS ping cycle is the authoritative detector.
	private void trackHealth(SipServletResponse legResponse) {
		try {
			String name = (String) legResponse.getRequest().getAttribute(ENDPOINT_NAME_ATTR);
			if (name == null) {
				return;
			}
			BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();
			EndpointHealth health = config.endpointHealth.get(name);
			if (health == null) {
				return;
			}

			int status = legResponse.getStatus();

			// traffic counters: every leg-final response is one attempt on
			// that endpoint (provisionals are not attempts); these power the
			// dashboard's traffic-share bars and pulse animation
			if (status >= 200) {
				health.recordAttempt();
				if (successful(legResponse)) {
					health.recordSuccess();
				} else if (shouldFailover(legResponse)) {
					health.recordFailover();
				}
			}

			if (successful(legResponse)) {
				health.markUp("INVITE " + status, "call", -1);
			} else if (status == 503) {
				Integer backoff = null;
				String header = legResponse.getHeader("Retry-After");
				if (header != null) {
					try {
						backoff = Integer.parseInt(header.trim());
					} catch (NumberFormatException ignore) {
						// duration only; ignore unparseable forms
					}
				}
				if (backoff == null) {
					Integer configured = config.getHealth().getDefaultBackoff();
					backoff = (configured != null && configured > 0) ? configured : null;
				}
				health.markDown("503" + (backoff != null ? " backoff " + backoff + "s" : ""), backoff, "call", -1);
			}
		} catch (Exception e) {
			sipLogger.warning(legResponse, "InviteCallflow.trackHealth - " + e.getMessage());
		}
	}

}
