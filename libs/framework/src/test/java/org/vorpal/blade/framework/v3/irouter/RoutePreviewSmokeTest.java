package org.vorpal.blade.framework.v3.irouter;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalRouting;
import org.vorpal.blade.framework.v3.configuration.routing.DirectRouting;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.irouter.RoutePreviewEngine.RouteResult;

/// Smoke-test driver for the irouter-editor's offline routing dry-run:
/// parse → inline pipeline → decide → resolved Route report.
public final class RoutePreviewSmokeTest {
	private static int passed;
	private static int failed;

	private static final class TestLogger extends Logger {
		private static final long serialVersionUID = 1L;
		TestLogger() { super("route-preview-smoke", null); }
	}

	public static void main(String[] args) throws Exception {
		Logger testLogger = new TestLogger();
		SettingsManager.setSipLogger(testLogger);
		Callflow.setLogger(testLogger);

		testForwardWithHeaders();
		testDirectResponse();
		testNoDecision();
		testPassthroughForward();
		testRejectsResponse();
		testInitialVariables();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	private static IRouterConfig config() {
		IRouterConfig cfg = new IRouterConfig();
		SipConnector sip = new SipConnector();
		sip.setId("sip");
		sip.addSelector(new RegexSelector("dialedNumber", "To", ".*<?sips?:\\+?(?<did>[^@;>]+)@.*", "${did}"));
		cfg.getPipeline().add(sip);
		return cfg;
	}

	private static String inviteTo(String user) {
		return "INVITE sip:" + user + "@pbx.example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:" + user + "@pbx.example.com>\r\n"
				+ "Call-ID: rp-" + user + "@vorpal.net\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "\r\n";
	}

	private static void testForwardWithHeaders() {
		IRouterConfig cfg = config();
		ConditionalRouting routing = new ConditionalRouting();
		routing.addClause("${dialedNumber} == 8001",
				new Route("sip:screening@10.0.0.9")
						.addHeader("X-Dialed", "${dialedNumber}")
						.addConditionalHeader("X-Priority", "high", "${dialedNumber} == 8001")
						.addConditionalHeader("X-Never", "no", "${dialedNumber} == 9999"));
		cfg.setRouting(routing);

		RouteResult r = RoutePreviewEngine.routePreview(cfg, inviteTo("8001"), null);
		check("route.forward.no-error", r.error == null);
		check("route.forward.outcome", "forward".equals(r.outcome));
		check("route.forward.uri", "sip:screening@10.0.0.9".equals(r.requestUri));
		check("route.forward.header-resolved", "8001".equals(r.headers.get("X-Dialed")));
		check("route.forward.conditional-applied", "high".equals(r.headers.get("X-Priority")));
		check("route.forward.conditional-skipped",
				r.skippedConditionalHeaders.size() == 1
						&& r.skippedConditionalHeaders.get(0).startsWith("X-Never"));
		check("route.forward.variables", "8001".equals(r.variables.get("dialedNumber")));
	}

	private static void testDirectResponse() {
		IRouterConfig cfg = config();
		ConditionalRouting routing = new ConditionalRouting();
		routing.addClause("${dialedNumber} == 8002", new Route(603, "Decline ${dialedNumber}"));
		cfg.setRouting(routing);

		RouteResult r = RoutePreviewEngine.routePreview(cfg, inviteTo("8002"), null);
		check("route.respond.outcome", "respond".equals(r.outcome));
		check("route.respond.status", r.statusCode != null && r.statusCode == 603);
		check("route.respond.reason-resolved", "Decline 8002".equals(r.reasonPhrase));
	}

	private static void testNoDecision() {
		IRouterConfig cfg = config();
		ConditionalRouting routing = new ConditionalRouting();
		routing.addClause("${dialedNumber} == 9999", new Route("sip:never@x"));
		cfg.setRouting(routing);

		RouteResult r = RoutePreviewEngine.routePreview(cfg, inviteTo("8003"), null);
		check("route.none.outcome", "none".equals(r.outcome));

		cfg.setRouting(null);
		RouteResult r2 = RoutePreviewEngine.routePreview(cfg, inviteTo("8003"), null);
		check("route.none.no-routing", "none".equals(r2.outcome));
	}

	private static void testPassthroughForward() {
		IRouterConfig cfg = config();
		// DirectRouting with no requestUri: always-forward, passthrough to
		// the inbound Request-URI.
		cfg.setRouting(new DirectRouting());

		RouteResult r = RoutePreviewEngine.routePreview(cfg, inviteTo("8004"), null);
		check("route.passthrough.outcome", "forward".equals(r.outcome));
		check("route.passthrough.null-uri", r.requestUri == null);
	}

	private static void testRejectsResponse() {
		RouteResult r = RoutePreviewEngine.routePreview(config(),
				"SIP/2.0 200 OK\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@y>;tag=2\r\nCSeq: 1 INVITE\r\n\r\n",
				null);
		check("route.response.rejected", r.error != null);
	}

	private static void testInitialVariables() {
		IRouterConfig cfg = config();
		ConditionalRouting routing = new ConditionalRouting();
		routing.addClause("${customerTier} == premium", new Route("sip:vip@10.0.0.9"));
		cfg.setRouting(routing);

		Map<String, String> vars = new HashMap<>();
		vars.put("customerTier", "premium");
		RouteResult r = RoutePreviewEngine.routePreview(cfg, inviteTo("8005"), vars);
		check("route.initial-vars.used", "forward".equals(r.outcome)
				&& "sip:vip@10.0.0.9".equals(r.requestUri));
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}
}
