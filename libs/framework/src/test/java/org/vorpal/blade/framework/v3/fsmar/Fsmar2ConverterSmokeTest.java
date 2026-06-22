package org.vorpal.blade.framework.v3.fsmar;

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
		testRepeatingHeaderAnyInstance();
		testRegionLabelAndQuotes();
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
		check("operators: contains over all instances", t.getWhen().contains("${Subject__all} contains '23'"));
		// To(addr) + From(attr) + Subject(attr, equals) + Subject(attr-all, contains)
		// + Contact(attr-all, param) = 5 distinct selectors.
		check("operators: selectors deduped per state", init.getSelectors().size() == 5);

		Map<String, Object> p = new HashMap<>();
		p.put("To", "<sip:ALICE@vorpal.net>");
		p.put("From", "\"Bob\" <sip:bob@example.com>;tag=1");
		p.put("Subject", "1234");
		p.put("Contact", "<sip:c@h.net;transport=tcp>");
		check("operators: all-AND payload fires", fires(init, t, p));

		p.put("To", "<sip:carol@vorpal.net>");
		check("operators: one failed clause kills it", !fires(init, t, p));
	}

	/// Repeating-header matching: fsmar2 scanned EVERY instance of a repeating
	/// header for `contains` / parameter operators. The converter emits an
	/// all-instances selector + a clause that is existential over the multi-value
	/// delimiter. Proven by feeding a context a multi-instance (delimiter-joined)
	/// value directly — exactly what the all-instances AttributeSelector produces
	/// from a repeating header on a live request.
	private static void testRepeatingHeaderAnyInstance() throws Exception {
		String nul = Context.MULTI_VALUE_DELIMITER;

		String viaCfg = "{"
				+ "\"previous\": { \"null\": { \"triggers\": { \"INVITE\": { \"transitions\": [ {"
				+ "  \"id\": \"V\", \"next\": \"screening\","
				+ "  \"condition\": { \"Via\": [ { \"contains\": \"10.0.0.5\" } ] } } ] } } } } }";
		Fsmar2Converter.Result r = Fsmar2Converter.convert(MAPPER.readTree(viaCfg));
		AppRouterConfiguration cfg = MAPPER.readValue(Fsmar2Converter.toValidatedJson(r),
				AppRouterConfiguration.class);
		Transition t = cfg.getStates().get("null").getTriggers().get("INVITE").getTransitions().get(0);
		check("repeating: contains targets the all-instances var",
				"${Via__all} contains '10.0.0.5'".equals(t.getWhen()));

		Context hit = new MemoryContext();
		hit.put("Via__all", "SIP/2.0/UDP 10.0.0.1" + nul + "SIP/2.0/UDP 10.0.0.5;branch=z9");
		check("repeating: contains fires when ANY instance matches", t.matches(hit));

		Context miss = new MemoryContext();
		miss.put("Via__all", "SIP/2.0/UDP 10.0.0.1" + nul + "SIP/2.0/UDP 10.0.0.9");
		check("repeating: contains does not fire when NO instance matches", !t.matches(miss));

		// Parameter operator across all instances (existential `matches`).
		String divCfg = "{"
				+ "\"previous\": { \"null\": { \"triggers\": { \"INVITE\": { \"transitions\": [ {"
				+ "  \"id\": \"D\", \"next\": \"b2bua\","
				+ "  \"condition\": { \"Diversion\": [ { \"reason\": \"unconditional\" } ] } } ] } } } } }";
		Fsmar2Converter.Result dr = Fsmar2Converter.convert(MAPPER.readTree(divCfg));
		Transition dt = MAPPER.readValue(Fsmar2Converter.toValidatedJson(dr), AppRouterConfiguration.class)
				.getStates().get("null").getTriggers().get("INVITE").getTransitions().get(0);

		Context dHit = new MemoryContext();
		dHit.put("Diversion__all",
				"<sip:a@h>;reason=user-busy" + nul + "<sip:b@h>;reason=unconditional;counter=1");
		check("repeating: param fires when ANY instance carries it", dt.matches(dHit));

		Context dMiss = new MemoryContext();
		dMiss.put("Diversion__all", "<sip:a@h>;reason=user-busy" + nul + "<sip:b@h>;reason=no-answer");
		check("repeating: param does not fire when no instance carries it", !dt.matches(dMiss));
	}

	/// Region-Label maps to the ${regionLabel} pseudo-variable (no longer
	/// fail-closed), and a match value containing a single quote is escaped into
	/// the Expression string literal (and still compiles + matches).
	private static void testRegionLabelAndQuotes() throws Exception {
		String fsmar2 = "{"
				+ "\"previous\": { \"null\": { \"triggers\": { \"INVITE\": { \"transitions\": [ {"
				+ "  \"id\": \"RL\", \"next\": \"b2bua\","
				+ "  \"condition\": {"
				+ "    \"Region-Label\": [ { \"equals\": \"core\" } ],"
				+ "    \"From\":         [ { \"equals\": \"O'Brien\" } ]"
				+ "  } } ] } } } } }";
		Fsmar2Converter.Result r = Fsmar2Converter.convert(MAPPER.readTree(fsmar2));
		// toValidatedJson compiles every `when` — proves the escaped literal parses.
		String json = Fsmar2Converter.toValidatedJson(r);
		check("region-label: not a review item", !r.needsReview());

		AppRouterConfiguration cfg = MAPPER.readValue(json, AppRouterConfiguration.class);
		Transition t = cfg.getStates().get("null").getTriggers().get("INVITE").getTransitions().get(0);
		check("region-label: maps to ${regionLabel}",
				t.getWhen().contains("${regionLabel} matches '(?i)core'"));
		check("quote: apostrophe escaped in the literal", t.getWhen().contains("O\\'Brien"));

		Context fire = new MemoryContext();
		fire.put("regionLabel", "core");
		fire.put("From", "O'Brien");
		check("quote: O'Brien matches the escaped literal", t.matches(fire));

		Context no = new MemoryContext();
		no.put("regionLabel", "core");
		no.put("From", "Smith");
		check("quote: a different value does not match", !t.matches(no));
	}

	/// Unconvertible conditions must emit when="false" (never fires) and a
	/// REVIEW warning — fail closed, never silently widen routing.
	private static void testFailClosed() throws Exception {
		String fsmar2 = "{"
				+ "\"previous\": { \"null\": { \"triggers\": { \"INVITE\": { \"transitions\": [ {"
				+ "  \"id\": \"BAD\", \"next\": \"b2bua\","
				// 'odd-op' is neither a known operator nor a legal parameter name
				// (the dash), so it cannot be converted — fail closed.
				+ "  \"condition\": { \"X-Custom\": [ { \"odd-op\": \"value\" } ] }"
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
