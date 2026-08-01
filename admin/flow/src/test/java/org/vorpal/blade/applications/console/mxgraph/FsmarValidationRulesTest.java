package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v3.configuration.selectors.Selector;
import org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration;

/// The two validator rules that exist because the config can be *accepted by
/// the editor* and then rejected or ignored by the engine.
///
/// Each rule is paired with a check against the real framework model, so the
/// rule can't drift away from the behaviour it is warning about.
class FsmarValidationRulesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// A mapper configured the way the engine's SettingsManager configures its
	/// own (`FAIL_ON_UNKNOWN_PROPERTIES` off) — see SettingsManager:457.
	private static ObjectMapper engineMapper() {
		ObjectMapper m = new ObjectMapper();
		m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return m;
	}

	private static List<String> errorsOf(String cfg) throws Exception {
		List<String> errors = new ArrayList<>();
		new FsmarValidateServlet().validate(MAPPER.readTree(cfg), errors,
				new ArrayList<>(), new ArrayList<>());
		return errors;
	}

	// ---- duplicate selector ids ------------------------------------------

	private static final String DUPLICATE_IDS = "{\"version\":3,\"states\":{"
			+ "\"null\":{\"selectors\":[{\"id\":\"From\",\"attribute\":\"From\"}],"
			+ "        \"triggers\":{\"INVITE\":{\"transitions\":[{\"next\":\"screening\"}]}}},"
			+ "\"screening\":{\"selectors\":[{\"id\":\"From\",\"attribute\":\"To\"}],"
			+ "        \"triggers\":{}}}}";

	@Test
	@DisplayName("a duplicate selector id no longer breaks the config")
	void duplicateSelectorIdStillLoads() throws Exception {
		// The whole point of dropping @JsonIdentityInfo from v3's Selector.
		// It used to throw "Already had POJO for id" and fail the entire
		// config — harmless on reload (last-good config kept routing) but a
		// 500-on-every-request cold start, so the mistake detonated on a later
		// restart. If this ever throws again, the annotation has been restored
		// and that latent failure is back.
		AppRouterConfiguration cfg =
				engineMapper().readValue(DUPLICATE_IDS, AppRouterConfiguration.class);

		assertNotNull(cfg, "config should deserialize");
		assertEquals("From", cfg.getStates().get("null").getSelectors().get(0).getAttribute());
		assertEquals("To", cfg.getStates().get("screening").getSelectors().get(0).getAttribute());
	}

	@Test
	@DisplayName("duplicate ids in one state stay two distinct selectors")
	void duplicateIdsInOneStateStayDistinct() throws Exception {
		// They must not collapse into a shared reference either — both run,
		// and the later one wins on the context variable.
		AppRouterConfiguration cfg = engineMapper().readValue(
				"{\"version\":3,\"states\":{\"null\":{\"selectors\":["
						+ "{\"id\":\"X\",\"attribute\":\"From\"},{\"id\":\"X\",\"attribute\":\"To\"}],"
						+ "\"triggers\":{}}}}",
				AppRouterConfiguration.class);

		List<Selector> sels = cfg.getStates().get("null").getSelectors();
		assertEquals(2, sels.size(), "both selectors should survive");
		assertNotSame(sels.get(0), sels.get(1), "they must not collapse into one object");
		assertEquals("From", sels.get(0).getAttribute());
		assertEquals("To", sels.get(1).getAttribute());
	}

	@Test
	@DisplayName("a reused selector id is still warned about")
	void duplicateSelectorIdIsWarned() throws Exception {
		// No longer fatal, still almost certainly a mistake: the id is the
		// context variable name, so the later selector overwrites the earlier.
		List<String> warnings = warningsOf(DUPLICATE_IDS);

		assertTrue(warnings.stream().anyMatch(w -> w.contains("reuses selector id 'From'")),
				() -> "duplicate selector id not reported: " + warnings);
		assertTrue(errorsOf(DUPLICATE_IDS).isEmpty(),
				"a duplicate id must not be an error any more");
	}

	@Test
	@DisplayName("distinct selector ids raise nothing")
	void distinctSelectorIdsAreClean() throws Exception {
		String cfg = DUPLICATE_IDS.replace(
				"{\"id\":\"From\",\"attribute\":\"To\"}", "{\"id\":\"callee\",\"attribute\":\"To\"}");
		List<String> errors = errorsOf(cfg);
		List<String> warnings = warningsOf(cfg);

		assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
		assertTrue(warnings.stream().noneMatch(w -> w.contains("reuses selector id")),
				() -> "unexpected duplicate-id warning: " + warnings);
	}

	// ---- app on the entry state ------------------------------------------

	private static List<String> warningsOf(String cfg) throws Exception {
		List<String> warnings = new ArrayList<>();
		new FsmarValidateServlet().validate(MAPPER.readTree(cfg), new ArrayList<>(),
				warnings, new ArrayList<>());
		return warnings;
	}

	@Test
	@DisplayName("app on the \"null\" state is flagged as having no effect")
	void appOnEntryStateIsFlagged() throws Exception {
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"app\":\"b2bua\",\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"next\":\"screening\"}]}}},"
				+ "\"screening\":{\"triggers\":{}}}}");

		assertTrue(warnings.stream().anyMatch(w -> w.contains(".app has no effect")),
				() -> "app on the entry state not flagged: " + warnings);
	}

	@Test
	@DisplayName("app on an ordinary state is not flagged")
	void appOnOrdinaryStateIsFine() throws Exception {
		// Two states sharing one application is the whole point of `app` —
		// the warning must not spill onto it.
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"next\":\"leg1\"}]}}},"
				+ "\"leg1\":{\"app\":\"b2bua\",\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"next\":\"leg2\"}]}}},"
				+ "\"leg2\":{\"app\":\"b2bua\",\"triggers\":{}}}}");

		assertTrue(warnings.stream().noneMatch(w -> w.contains(".app has no effect")),
				() -> "app on an ordinary state should be fine: " + warnings);
	}

	// ---- constant conditions ---------------------------------------------

	@Test
	@DisplayName("a when testing ${previousState} is flagged as a constant")
	void previousStateInWhenIsFlagged() throws Exception {
		// This reads like "only on the first hop" and is in fact always true,
		// which makes the transition below it dead for every call.
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"INV-1\",\"when\":\"${previousState} == 'null'\",\"next\":\"screening\"},"
				+ "  {\"id\":\"INV-2\",\"when\":\"${To.user} == 'bob'\",\"next\":\"b2bua\"}]}}},"
				+ "\"screening\":{\"triggers\":{}},\"b2bua\":{\"triggers\":{}}}}");

		assertTrue(warnings.stream().anyMatch(
				w -> w.contains("${previousState}") && w.contains("always 'null'")),
				() -> "constant condition not flagged: " + warnings);
	}

	@Test
	@DisplayName("${previousApp} reports the state's app, not its id")
	void previousAppReportsResolvedApp() throws Exception {
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"next\":\"leg1\"}]}}},"
				+ "\"leg1\":{\"app\":\"b2bua\",\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"when\":\"${previousApp} == 'b2bua'\",\"next\":\"registrar\"}]}}},"
				+ "\"registrar\":{\"triggers\":{}}}}");

		assertTrue(warnings.stream().anyMatch(
				w -> w.contains("${previousApp}") && w.contains("always 'b2bua'")),
				() -> "should name the resolved app: " + warnings);
	}

	@Test
	@DisplayName("the same variables in a route are not flagged")
	void previousStateInRouteIsFine() throws Exception {
		// Naming the application that just ran is exactly what they are for.
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"when\":\"${To.user} == 'bob'\","
				+ "   \"routes\":[\"sip:edge.example.com;src=${previousApp}\"],"
				+ "   \"routeModifier\":\"ROUTE\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{}}}}");

		assertTrue(warnings.stream().noneMatch(w -> w.contains("is a constant")),
				() -> "route templates should not be flagged: " + warnings);
	}

	// ---- route-back drawn as an ordinary hop -----------------------------

	@Test
	@DisplayName("next + ROUTE_BACK + routes is flagged as a route-back, not a hop")
	void routeBackToAStateIsFlagged() throws Exception {
		// Now that the routes editor is shown on app-to-app transitions, this
		// shape is drawable from a fresh edge. It looks like a hop to
		// 'screening' and is not one.
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"INV-1\",\"next\":\"screening\","
				+ "   \"routes\":[\"sip:sbc.example.com\"],\"routeModifier\":\"ROUTE_BACK\"}]}}},"
				+ "\"screening\":{\"triggers\":{}}}}");

		assertTrue(warnings.stream().anyMatch(w -> w.contains("is a route-back, not a hop")),
				() -> "route-back shape not flagged: " + warnings);
	}

	@Test
	@DisplayName("routes with ROUTE on an app-to-app hop are not flagged")
	void routesWithPlainRouteAreFine() throws Exception {
		// "Invoke this app AND push a Route header" — the shape unhiding the
		// editor was meant to enable. It must stay quiet.
		List<String> warnings = warningsOf("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"INV-1\",\"next\":\"screening\","
				+ "   \"routes\":[\"sip:edge.example.com;lr\"],\"routeModifier\":\"ROUTE\"}]}}},"
				+ "\"screening\":{\"triggers\":{}}}}");

		assertTrue(warnings.stream().noneMatch(w -> w.contains("route-back")),
				() -> "a plain ROUTE hop should not be flagged: " + warnings);
	}

	// ---- in-dialog trigger methods ---------------------------------------

	@Test
	@DisplayName("a BYE trigger is an error — the router never sees in-dialog requests")
	void inDialogTriggerMethodIsAnError() throws Exception {
		List<String> errors = errorsOf("{\"version\":3,\"states\":{\"null\":{\"triggers\":{"
				+ "\"BYE\":{\"transitions\":[{\"next\":\"cleanup\"}]}}},"
				+ "\"cleanup\":{\"triggers\":{}}}}");

		assertTrue(errors.stream().anyMatch(e -> e.contains("can never fire")),
				() -> "in-dialog trigger method not reported: " + errors);
	}

	@Test
	@DisplayName("every initial-request method is accepted")
	void initialRequestMethodsAreAccepted() throws Exception {
		for (String method : FsmarValidateServlet.INITIAL_REQUEST_METHODS) {
			List<String> errors = errorsOf("{\"version\":3,\"states\":{\"null\":{\"triggers\":{\""
					+ method + "\":{\"transitions\":[{\"next\":\"app\"}]}}},"
					+ "\"app\":{\"triggers\":{}}}}");

			assertTrue(errors.isEmpty(),
					() -> "method " + method + " should be valid, got: " + errors);
		}
	}
}
