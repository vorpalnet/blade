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

import org.vorpal.blade.framework.Callback;
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
/// Failover is response-classified ([#shouldFailover], [FailoverPolicy]):
/// the responses in `health.failoverOn` (default 408, 502, 503, 504) move the
/// call to the tier's next endpoint, then to the next tier. Everything else,
/// a 404, a 486, a challenge, a 6xx, goes straight back to the caller: every
/// endpoint would answer it the same. Nothing routable answers 500, and a
/// relayed 503 goes up as a 500 ([#upstreamStatus]).
///
/// The per-dialog observer relays real 18x ringing upstream and passively marks
/// endpoint health (2xx up; `health.markDownOn`, default 503 and a 408 the
/// container made because nothing came back, down). With `session:passthru` set, the winning dialog
/// drops out of the dialog after setup.
public class InviteCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Dialog-request attribute carrying the endpoint's registry NAME, so the
	/// observer can mark health without reverse-mapping URIs.
	static final String ENDPOINT_NAME_ATTR = "balancer.endpointName";

	/// Caller-request attribute, suffixed with the tier index: the branch whose
	/// early-media SDP the caller was given, in a parallel tier.
	static final String EARLY_MEDIA_ATTR = "balancer.earlyMedia.";

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

		// Ring the caller immediately; real 18x from the dialogs is also relayed
		// (via the per-dialog observer), but until a dialog responds the caller's UA
		// generates local ringback from this one. Not under passthru: the call
		// goes out as a proxy, and a proxy's caller hears only the callees.
		if (!isPassthru()) {
			sendResponse(aliceRequest.createResponse(180));
		}

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
				copyContentAndHeaders(aliceRequest, bobRequest); // links each dialog back to alice
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
				// 500, not 503: the routes are down, not this engine (see #upstreamStatus)
				BalancerEvents.declined(aliceRequest, 503, tierIndex);
				if (!proxied(aliceRequest)) {
					sendResponse(aliceRequest.createResponse(500));
				} // else the proxy answers with the last tier's failure
			}
			return;
		}

		// Every response from every dialog: passive health marking, and relay of
		// real ringing (180/183) upstream. 100 Trying is hop-by-hop.
		//
		// A parallel tier has one caller dialog and several branches, and a
		// caller takes one early-media answer per early dialog: a second 183
		// with different SDP, and a 200 with a third, leave many phones and SBCs
		// playing the first and ignoring the rest. So only the first branch to
		// offer early media gets its SDP relayed; the others' provisionals go up
		// without a body (the caller still learns they are ringing). If that
		// branch fails, the next one to offer SDP takes its place.
		boolean parallel = tier.getStrategy() == Tier.Strategy.parallel;
		String earlyMedia = EARLY_MEDIA_ATTR + tierIndex;
		Callback<SipServletResponse> dialogObserver = (dialogResponse) -> {
			trackHealth(dialogResponse);
			if (isPassthru()) {
				return; // the proxy relays every branch's ringing itself
			}
			int status = dialogResponse.getStatus();
			String branch = dialogResponse.getSession().getId();
			if (status >= 300 && branch.equals(aliceRequest.getAttribute(earlyMedia))) {
				aliceRequest.removeAttribute(earlyMedia);
			}
			if (status > 100 && status < 200 && !aliceRequest.isCommitted()) {
				SipServletResponse aliceRinging = aliceRequest.createResponse(status);
				boolean relaySdp = true;
				if (parallel && dialogResponse.getContentLength() > 0) {
					Object owner = aliceRequest.getAttribute(earlyMedia);
					if (owner == null) {
						aliceRequest.setAttribute(earlyMedia, branch);
					} else {
						relaySdp = branch.equals(owner);
					}
				}
				if (relaySdp) {
					copyContentAndHeaders(dialogResponse, aliceRinging);
				} else {
					copyHeaders(dialogResponse, aliceRinging);
				}
				sendResponse(aliceRinging);
			}
		};

		Callback<SipServletResponse> tierComplete = (bobResponse) -> {

			// Passthru: the container answers the caller with what the proxy
			// got. Ours are the next tier and the record; a caller's CANCEL
			// arrives here as a 487, which never fails over.
			if (isPassthru()) {
				if (!successful(bobResponse) && !plan.isEmpty() && shouldFailover(bobResponse)) {
					forwardTier(aliceRequest, plan, planKey, tierIndex + 1);
				} else if (successful(bobResponse)) {
					BalancerEvents.routed(aliceRequest, bobResponse, tier.getName(), tierIndex);
				} else {
					BalancerEvents.declined(aliceRequest, bobResponse.getStatus(), tierIndex);
				}
				return;
			}

			// The isCommitted() guard matters: a caller who CANCELed has
			// already been answered 487 by Terminate — the losing dialog's 487
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

				int status = bobResponse.getStatus();
				SipServletResponse aliceResponse = aliceRequest
						.createResponse(successful(bobResponse) ? status : upstreamStatus(status));
				copyContentAndHeaders(bobResponse, aliceResponse); // links alice to the winning dialog
				if (status == 503) {
					// the endpoint's backoff is for its own callers, not ours
					aliceResponse.removeHeader("Retry-After");
				}

				if (successful(bobResponse)) {
					BalancerEvents.routed(aliceRequest, bobResponse, tier.getName(), tierIndex);
					sendResponse(aliceResponse, (aliceAck) -> {
						sendAcknowledgement(aliceAck, bobResponse);
					});
				} else {
					BalancerEvents.declined(aliceRequest, bobResponse.getStatus(), tierIndex);
					sendResponse(aliceResponse);
				}

			}

		};

		Integer timeout = tier.getTimeout();
		long timeoutMillis = ((timeout != null && timeout > 0) ? timeout : 180) * 1000L;

		if (tier.getStrategy() == Tier.Strategy.parallel) {
			// the v3 race: first 2xx wins, a late 2xx is ended, a 6xx stops it,
			// and a definite failure outranks one failoverOn would retry
			sendRequestsInParallel(timeoutMillis, bobRequests, tierComplete, dialogObserver,
					InviteCallflow::shouldFailover);
		} else {
			// all hunt strategies: the ordering above IS the strategy, and the
			// same failoverOn list decides whether the hunt moves on
			sendRequestsInSerial(timeoutMillis, bobRequests, tierComplete, dialogObserver,
					InviteCallflow::shouldFailover);
		}

	}

	/// Has the caller's INVITE already gone out as a passthru proxy?
	private static boolean proxied(SipServletRequest aliceRequest) {
		try {
			return aliceRequest.getProxy(false) != null;
		} catch (Exception e) {
			return false;
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

	/// The status the caller gets for an endpoint's failure: a 503 becomes a
	/// 500. A 503 from this engine tells the caller's load balancer that this
	/// engine is overloaded, and it backs off the engine, when it was one
	/// endpoint, or every route, that refused. RFC 3261 §16.7 asks the same of
	/// a proxy that forwards a 503 upstream.
	static int upstreamStatus(int status) {
		return status == 503 ? 500 : status;
	}

	/// Should this final response try the next endpoint or tier? The
	/// balancer's `health.failoverOn` list decides; see [FailoverPolicy].
	private static boolean shouldFailover(SipServletResponse response) {
		BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();
		return FailoverPolicy.failover(response.getStatus(), config != null ? config.getHealth() : null);
	}

	/// Passive health marking from a live dialog response: 2xx proves the
	/// endpoint alive; a response in `health.markDownOn` takes it out of
	/// service ([FailoverPolicy#markDown]). A 503 is a refusal, Retry-After
	/// honored, else the configured defaultBackoff. A 408 counts only when the
	/// container made it because nothing came back: such a response has no
	/// sender address, while one that arrived from the endpoint names it. User
	/// responses (486 busy, 603 decline, other 4xx) say nothing about the
	/// endpoint and are ignored; the OPTIONS ping cycle is the authoritative
	/// detector.
	private void trackHealth(SipServletResponse dialogResponse) {
		try {
			String name = (String) dialogResponse.getRequest().getAttribute(ENDPOINT_NAME_ATTR);
			if (name == null) {
				return;
			}
			BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();
			EndpointHealth health = config.endpointHealth.get(name);
			if (health == null) {
				return;
			}

			int status = dialogResponse.getStatus();

			// traffic counters: every dialog-final response is one attempt on
			// that endpoint (provisionals are not attempts); these power the
			// dashboard's traffic-share bars and pulse animation
			// A 487 is a branch we cancelled (it lost a race, or the caller
			// hung up): not an attempt the endpoint failed.
			if (status >= 200 && status != 487) {
				health.recordAttempt();
				if (successful(dialogResponse)) {
					health.recordSuccess();
				} else if (shouldFailover(dialogResponse)) {
					health.recordFailover();
				}
			}

			if (successful(dialogResponse)) {
				if (health.markUp("INVITE " + status, "call", -1)) {
					BalancerEvents.up(name, health);
				}
			} else if (status >= 300
					&& FailoverPolicy.markDown(status, dialogResponse.getRemoteAddr() != null, config.getHealth())) {
				Integer backoff = null;
				String header = dialogResponse.getHeader("Retry-After");
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
				boolean changed = status == 503
						? health.markRefused(status, dialogResponse.getReasonPhrase(), backoff, "call", -1)
						: health.markDown("INVITE " + status + (dialogResponse.getRemoteAddr() == null ? ", no answer" : ""),
								backoff, "call", -1);
				if (changed) {
					BalancerEvents.down(name, health, backoff);
				}
			}
		} catch (Exception e) {
			sipLogger.warning(dialogResponse, "InviteCallflow.trackHealth - " + e.getMessage());
		}
	}

}
