package org.vorpal.blade.library.fsmar3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.ar.SipApplicationRouterInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration;
import org.vorpal.blade.framework.v3.fsmar.Transition;
import org.vorpal.blade.framework.v3.fsmar.Trigger;

/// The defaultApplication fallback rule: it fires for **"no matches
/// whatsoever"** on a fresh external composition, and for nothing else.
///
/// A matched transition that deliberately selects no application — a
/// stop/downstream exit, an egress, an empty trigger's implicit match — is a
/// decision, not a miss, and must not be clobbered by the fallback
/// (`AppRouter`'s `anyMatch` gate). App-originated sends start the FSM after
/// the originating app and must never land on the default (the `startState`
/// guard).
///
/// Runs the REAL `getNextApplication` outside the container: the config rides
/// in on a pre-bound [RoutingState] (so the SettingsManager is never touched)
/// and the request is a reflection proxy answering only the methods the walk
/// reads. The correlation-id block tolerates the missing container
/// (`catch (Throwable)` — servlet static init fails outside OCCAS).
class AppRouterFallbackTest {

	@AfterEach
	void clearDeployed() {
		AppRouter.deployed.clear();
	}

	/// A minimal SipServletRequest: an INVITE with a Call-ID and nothing else.
	/// Unstubbed methods return null/0/false — every use in the walk is
	/// null-guarded.
	private static SipServletRequest request() {
		return (SipServletRequest) Proxy.newProxyInstance(
				SipServletRequest.class.getClassLoader(),
				new Class<?>[] { SipServletRequest.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
					case "getMethod":
						return "INVITE";
					case "getCallId":
						return "cid-fallback-test";
					default:
						Class<?> rt = method.getReturnType();
						if (rt == boolean.class) return false;
						if (rt == int.class) return 0;
						if (rt == long.class) return 0L;
						return null;
					}
				});
	}

	/// Routes one invocation starting at `startState`, with the config bound
	/// up front (a continuation hop in engine terms — the walk and the
	/// fallback gate are identical to a fresh composition entering at the
	/// same state).
	private static SipApplicationRouterInfo route(AppRouterConfiguration cfg, String startState) {
		RoutingState rs = new RoutingState(cfg);
		rs.setCurrentStateId(startState);
		return new AppRouter().getNextApplication(request(), null, null, null, rs);
	}

	private static AppRouterConfiguration configWithDefault() {
		AppRouterConfiguration cfg = new AppRouterConfiguration();
		cfg.setDefaultApplication("b2bua");
		return cfg;
	}

	@Test
	@DisplayName("no match at the entry state falls back to the default application")
	void noMatchFallsBackToDefault() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		cfg.getState("null").getTrigger("INVITE")
				.createTransition("screening").setWhen("${x} == 'never'");

		SipApplicationRouterInfo info = route(cfg, "null");

		assertNotNull(info, "the fallback should have routed to the default");
		assertEquals("b2bua#1.0", info.getNextApplicationName());
	}

	@Test
	@DisplayName("a matched stop at the entry state beats the default application")
	void matchedStopSuppressesDefault() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		// Unconditional stop: matched, selects nothing — route downstream.
		cfg.getState("null").getTrigger("INVITE").createTransition(null);

		assertNull(route(cfg, "null"),
				"a matched stop is a decision — the fallback must not clobber it");
	}

	@Test
	@DisplayName("an empty trigger's implicit match beats the default application")
	void emptyTriggerSuppressesDefault() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		cfg.getState("null").getTrigger("INVITE"); // trigger exists, no transitions

		assertNull(route(cfg, "null"),
				"the implicit match counts as a match — downstream, not the default");
	}

	@Test
	@DisplayName("a matched egress at the entry state beats the default application")
	void egressSuppressesDefault() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		Trigger trigger = cfg.getState("null").getTrigger("INVITE");
		Transition egress = trigger.createTransition(null);
		egress.setRoutes(new String[] { "sip:gw.example.com" });

		SipApplicationRouterInfo info = route(cfg, "null");

		assertNotNull(info, "an egress is a routing decision");
		assertNull(info.getNextApplicationName(), "an egress selects no application");
		assertEquals("sip:gw.example.com", info.getRoutes()[0]);
	}

	@Test
	@DisplayName("a match into a virtual state that dead-ends stays off the default")
	void virtualBypassDeadEndSuppressesDefault() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		// null -> virtual-sbc (not deployed): bypass runs its trigger, which
		// matches nothing -> downstream. The classification WAS a match.
		cfg.getState("null").getTrigger("INVITE").createTransition("virtual-sbc");
		cfg.getState("virtual-sbc").getTrigger("INVITE")
				.createTransition("screening").setWhen("${x} == 'never'");

		assertNull(route(cfg, "null"),
				"a bypassed walk that dead-ends routes downstream, not to the default");
	}

	@Test
	@DisplayName("an app-originated send with no match returns null, never the default")
	void appOriginatedNoMatchReturnsNull() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		// The FSM starts AFTER the originating app (e.g. proxy-balancer's
		// OPTIONS health ping) — no state entry, nothing matches, and the
		// request must go out to the wire, not to the default application.
		assertNull(route(cfg, "proxy-balancer"),
				"app-originated sends must egress, not land on the default");
	}

	@Test
	@DisplayName("a match onto a deployed application routes there")
	void matchToDeployedAppRoutes() {
		AppRouter.deployed.put("b2bua", "b2bua#1.0");
		AppRouter.deployed.put("screening", "screening#1.0");
		AppRouterConfiguration cfg = configWithDefault();
		cfg.getState("null").getTrigger("INVITE").createTransition("screening");

		SipApplicationRouterInfo info = route(cfg, "null");

		assertNotNull(info);
		assertEquals("screening#1.0", info.getNextApplicationName());
	}
}
