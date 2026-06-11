package org.vorpal.blade.library.fsmar3;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke test for the FSMAR 2 → FSMAR 3 configuration converter.
///
/// Beyond shape checks, the conversions are verified BEHAVIORALLY: the
/// converted state's selectors run against a synthetic request payload and
/// the transition's `when` is evaluated — proving the converted config makes
/// the same routing decision fsmar2 made.
///
/// Run via `main`, like the other v3 smoke tests.
public final class Fsmar2ConverterSmokeTest {
	private static int passed;
	private static int failed;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(String[] args) throws Exception {
		testMediaHubShapedConfig();
		testOperatorSpread();
		testFailClosed();
		testRouteKindsAndRegions();
		summary();
	}

	/// The production shape: default app, unconditional OPTIONS route, and an
	/// INVITE matched on the Contact header's host IP.
	private static void testMediaHubShapedConfig() throws Exception {
		String fsmar2 = "{"
				+ "\"defaultApplication\": \"b2bua\","
				+ "\"previous\": { \"null\": { \"triggers\": {"
				+ "  \"OPTIONS\": { \"transitions\": [ { \"id\": \"OPT01\", \"next\": \"options\" } ] },"
				+ "  \"INVITE\":  { \"transitions\": [ { \"id\": \"INV01\", \"next\": \"recorder\","
				+ "    \"condition\": { \"Contact\": [ { \"host\": \"192.0.2.71\" } ] } } ] }"
				+ "} } } }";

		Fsmar2Converter.Result r = Fsmar2Converter.convert(MAPPER.readTree(fsmar2));
		String json = Fsmar2Converter.toValidatedJson(r);

		check("mediahub-shape: no review items", !r.needsReview());
		check("mediahub-shape: default app carried", "b2bua".equals(r.config.getDefaultApplication()));

		AppRouterConfiguration cfg = MAPPER.readValue(json, AppRouterConfiguration.class);
		State init = cfg.getStates().get("null");
		check("mediahub-shape: null state exists", init != null);

		Transition opt = init.getTriggers().get("OPTIONS").getTransitions().get(0);
		check("mediahub-shape: OPTIONS unconditional", opt.getWhen() == null);
		check("mediahub-shape: OPTIONS next", "options".equals(opt.getNext()));

		Transition inv = init.getTriggers().get("INVITE").getTransitions().get(0);
		check("mediahub-shape: INVITE when is clean ==",
				"${Contact.host} == '192.0.2.71'".equals(inv.getWhen()));

		// Behavioral: a request whose Contact carries the IP fires; another doesn't.
		check("mediahub-shape: matching Contact fires",
				fires(init, inv, payload("Contact", "<sip:rec@192.0.2.71:5060;transport=tcp>")));
		check("mediahub-shape: port not mistaken for host",
				fires(init, inv, payload("Contact", "<sip:rec@192.0.2.71>")));
		check("mediahub-shape: other host does not fire",
				!fires(init, inv, payload("Contact", "<sip:rec@192.0.2.99:5060>")));
	}

	/// One transition exercising user, equals, matches, contains, and a
	/// parameter operator, ANDed the way fsmar2 ANDed them.
	private static void testOperatorSpread() throws Exception {
		String fsmar2 = "{"
				+ "\"previous\": { \"null\": { \"triggers\": { \"INVITE\": { \"transitions\": [ {"
				+ "  \"id\": \"T1\", \"next\": \"screening\","
				+ "  \"condition\": {"
				+ "    \"To\":      [ { \"user\": \"alice\" } ],"
				+ "    \"From\":    [ { \"matches\": \".*example\\\\.com.*\" } ],"
				+ "    \"Subject\": [ { \"equals\": \"1234\" }, { \"contains\": \"23\" } ],"
				+ "    \"Contact\": [ { \"transport\": \"tcp\" } ]"
				+ "  } } ] } } } } }";

		Fsmar2Converter.Result r = Fsmar2Converter.convert(MAPPER.readTree(fsmar2));
		String json = Fsmar2Converter.toValidatedJson(r); // also compiles every when
		AppRouterConfiguration cfg = MAPPER.readValue(json, AppRouterConfiguration.class);
		State init = cfg.getStates().get("null");
		Transition t = init.getTriggers().get("INVITE").getTransitions().get(0);

		check("operators: when compiled and ANDed", t.getWhen() != null && t.getWhen().contains(" && "));
		check("operators: user is case-insensitive",
				t.getWhen().contains("${To.user} matches '(?i)alice'"));
		check("operators: digits use clean ==", t.getWhen().contains("${Subject} == '1234'"));
		check("operators: contains carried", t.getWhen().contains("${Subject} contains '23'"));
		check("operators: selectors deduped per state", init.getSelectors().size() == 4);

		Map<String, Object> p = new HashMap<>();
		p.put("To", "<sip:ALICE@vorpal.net>");
		p.put("From", "\"Bob\" <sip:bob@example.com>;tag=1");
		p.put("Subject", "1234");
		p.put("Contact", "<sip:c@h.net;transport=tcp>");
		check("operators: all-AND payload fires", fires(init, t, p));

		p.put("To", "<sip:carol@vorpal.net>");
		check("operators: one failed clause kills it", !fires(init, t, p));
	}

	/// Unconvertible conditions must emit when="false" (never fires) and a
	/// REVIEW warning — fail closed, never silently widen routing.
	private static void testFailClosed() throws Exception {
		String fsmar2 = "{"
				+ "\"previous\": { \"null\": { \"triggers\": { \"INVITE\": { \"transitions\": [ {"
				+ "  \"id\": \"BAD\", \"next\": \"b2bua\","
				+ "  \"condition\": { \"Region-Label\": [ { \"equals\": \"core\" } ] }"
				+ "} ] } } } } }";

		Fsmar2Converter.Result r = Fsmar2Converter.convert(MAPPER.readTree(fsmar2));
		Fsmar2Converter.toValidatedJson(r); // "false" must still compile
		Transition t = r.config.getStates().get("null")
				.getTriggers().get("INVITE").getTransitions().get(0);

		check("fail-closed: when is false", "false".equals(t.getWhen()));
		check("fail-closed: REVIEW item present", r.needsReview());
		check("fail-closed: transition never fires", !fires(r.config.getStates().get("null"), t,
				payload("To", "<sip:x@y.net>")));
	}

	/// originating/terminating → subscriber+region; route kinds → routes+modifier.
	private static void testRouteKindsAndRegions() throws Exception {
		String fsmar2 = "{"
				+ "\"previous\": { \"screening\": { \"triggers\": { \"INVITE\": { \"transitions\": ["
				+ " { \"id\": \"R1\", \"next\": \"b2bua\","
				+ "   \"action\": { \"terminating\": \"To\", \"route\": [ \"sip:core.example.net\" ] } },"
				+ " { \"id\": \"R2\", \"next\": \"proxy\","
				+ "   \"action\": { \"originating\": \"From\", \"route_back\": [ \"sip:edge.example.net\" ] } }"
				+ "] } } } } }";

		Fsmar2Converter.Result r = Fsmar2Converter.convert(MAPPER.readTree(fsmar2));
		String json = Fsmar2Converter.toValidatedJson(r);
		AppRouterConfiguration cfg = MAPPER.readValue(json, AppRouterConfiguration.class);
		java.util.List<Transition> ts = cfg.getStates().get("screening")
				.getTriggers().get("INVITE").getTransitions();

		check("routes: order preserved", "R1".equals(ts.get(0).getId()) && "R2".equals(ts.get(1).getId()));
		check("routes: terminating mapped", ts.get(0).getRegion() == Transition.Region.TERMINATING
				&& "To".equals(ts.get(0).getSubscriber()));
		check("routes: plain route, default modifier", ts.get(0).getRouteModifier() == null
				&& ts.get(0).getRoutes().length == 1);
		check("routes: originating mapped", ts.get(1).getRegion() == Transition.Region.ORIGINATING
				&& "From".equals(ts.get(1).getSubscriber()));
		check("routes: route_back modifier", ts.get(1).getRouteModifier() == javax.servlet.sip.ar.SipRouteModifier.ROUTE_BACK);
	}

	// ------------------------------------------------------------------

	/// Run the state's selectors over the payload, then evaluate the
	/// transition — exactly what AppRouter.getNextApplication does per hop.
	private static boolean fires(State state, Transition t, Map<String, Object> payload) {
		Context ctx = new MemoryContext(new HashMap<>());
		state.extract(ctx, payload);
		return t.matches(ctx);
	}

	private static Map<String, Object> payload(String header, String value) {
		Map<String, Object> p = new HashMap<>();
		p.put(header, value);
		return p;
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}

	private static void summary() {
		System.out.println("Fsmar2ConverterSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

}
