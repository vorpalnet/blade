package org.vorpal.blade.services.proxy.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Runs the sample's pipeline and routing against header values, the way the
/// iRouter does on an INVITE. The SIP connector's selectors read a `Map` of
/// headers here instead of a live request; every other stage is unchanged.
class CallBlockingConfigSampleTest {

	private static final String RURI = "sip:+18005550199@sbc.example.com;user=phone";

	private final IRouterConfig sample = new CallBlockingConfigSample();

	private static Map<String, String> invite(String from) {
		Map<String, String> h = new HashMap<>();
		h.put("From", from);
		h.put("requestURI", RURI);
		return h;
	}

	private static MemoryContext run(IRouterConfig config, Map<String, String> headers) {
		MemoryContext ctx = new MemoryContext();
		for (Connector c : config.getPipeline()) {
			if (c instanceof SipConnector) {
				for (Selector s : c.getSelectors()) s.extract(ctx, headers);
			} else {
				c.invoke(ctx).join();
			}
		}
		return ctx;
	}

	/// The sample with its STIR/SHAKEN switch set to true, as an operator would.
	private static IRouterConfig stirOn() {
		IRouterConfig config = new CallBlockingConfigSample();
		for (Selector s : config.getPipeline().get(0).getSelectors()) {
			if ("stirShaken".equals(s.getId())) ((RegexSelector) s).setExpression("true");
		}
		return config;
	}

	/// Every conditional header that applies, by name; later ones overwrite.
	private static Map<String, String> wireHeaders(Route route, MemoryContext ctx) {
		Map<String, String> out = new HashMap<>(route.getHeaders());
		for (ConditionalHeader ch : route.getConditionalHeaders()) {
			if (ch.shouldApply(ctx)) out.put(ch.getName(), ctx.resolve(ch.getValue()));
		}
		return out;
	}

	private Route decide(Map<String, String> headers) {
		return decide(sample, headers);
	}

	private static Route decide(IRouterConfig config, Map<String, String> headers) {
		return config.getRouting().decide(run(config, headers));
	}

	/// The X-Call-Screen value that would reach the wire: the unconditional
	/// header, overwritten by each conditional one that applies, in order.
	private static String screen(Route route, MemoryContext ctx) {
		String value = route.getHeaders().get("X-Call-Screen");
		if (route.getConditionalHeaders() != null) {
			for (ConditionalHeader ch : route.getConditionalHeaders()) {
				if ("X-Call-Screen".equals(ch.getName()) && ch.shouldApply(ctx)) value = ch.getValue();
			}
		}
		return value;
	}

	@Test
	void allowListPasses() {
		Route r = decide(invite("<sip:+18165550100@carrier.example.com>;tag=1"));
		assertNull(r.getStatusCode());
		assertNull(r.getRequestUri());
		assertEquals("allow;reason=allow-list", r.getHeaders().get("X-Call-Screen"));
	}

	@Test
	void reviewTreatmentForwardsToTheReviewMailbox() {
		Map<String, String> h = invite("<sip:2025550150@carrier.example.com>;tag=1");
		Route r = decide(h);
		assertNull(r.getStatusCode());
		assertEquals(CallBlockingConfigSample.REVIEW_URI, r.getRequestUri());
		assertEquals("review;reason=block-list", r.getHeaders().get("X-Call-Screen"));

		MemoryContext ctx = run(sample, h);
		assertEquals("<" + RURI + ">;reason=unconditional;counter=1", ctx.resolve(r.getHeaders().get("Diversion")));
		assertEquals(RURI, ctx.resolve(r.getHeaders().get("X-Original-Request-URI")));
	}

	@Test
	void tarpitTreatmentForwardsToTheTarpit() {
		Route listed = decide(invite("<sip:2025550160@carrier.example.com>;tag=1"));
		assertEquals(CallBlockingConfigSample.TARPIT_URI, listed.getRequestUri());
		assertEquals("tarpit;reason=block-list", listed.getHeaders().get("X-Call-Screen"));

		Route prefix = decide(invite("\"Robo\" <tel:+13035550199>;tag=1"));
		assertEquals(CallBlockingConfigSample.TARPIT_URI, prefix.getRequestUri());
	}

	@Test
	void declineTreatmentAnswers603() {
		Route r = decide(invite("<sip:2025550170@carrier.example.com>;tag=1"));
		assertEquals(603, r.getStatusCode());
		assertEquals("block;reason=block-list", r.getHeaders().get("X-Call-Screen"));
	}

	@Test
	void ownNumberGoesToTheTarpit() {
		Route r = decide(invite("<tel:+18005550100>;tag=1"));
		assertEquals(CallBlockingConfigSample.TARPIT_URI, r.getRequestUri());
		assertEquals("tarpit;reason=own-number", r.getHeaders().get("X-Call-Screen"));
	}

	@Test
	void assertedIdentityOverridesFrom() {
		// From claims an allow-listed number; the network-asserted identity is our own.
		Map<String, String> h = invite("<sip:+18165550100@carrier.example.com>;tag=1");
		h.put("P-Asserted-Identity", "<sip:+18885550100@carrier.example.com;user=phone>");
		assertEquals("tarpit;reason=own-number", decide(h).getHeaders().get("X-Call-Screen"));
	}

	@Test
	void invalidNumbersAnswer603() {
		for (String from : new String[] {
				"<sip:+11235550100@carrier.example.com>", // area code starts with 1
				"<sip:4115550100@carrier.example.com>", // N11 area code
				"<sip:2915550100@carrier.example.com>", // area code middle digit 9
				"<sip:8161550100@carrier.example.com>" }) { // exchange starts with 1
			Route r = decide(invite(from));
			assertEquals(603, r.getStatusCode(), from);
			assertEquals("block;reason=invalid-number", r.getHeaders().get("X-Call-Screen"), from);
		}
	}

	private static final String VERSTAT_FAILED =
			"<sip:+16125550100;verstat=TN-Validation-Failed@carrier.example.com;user=phone>;tag=1";

	@Test
	void stirShakenIsOffByDefault() {
		Map<String, String> failed = invite(VERSTAT_FAILED);
		Route r = decide(failed);
		assertNull(r.getRequestUri());
		Map<String, String> wire = wireHeaders(r, run(sample, failed));
		assertEquals("clear", wire.get("X-Call-Screen"));
		assertNull(wire.get("X-Call-Screen-Verstat"));

		Map<String, String> signed = invite("<sip:+16125550100@carrier.example.com>;tag=1");
		signed.put("Identity", passport("C", System.currentTimeMillis() / 1000 - 600));
		wire = wireHeaders(decide(signed), run(sample, signed));
		assertEquals("clear", wire.get("X-Call-Screen"));
		assertNull(wire.get("X-Call-Screen-Attest"));
	}

	@Test
	void failedVerificationGoesToChallenge() {
		Route r = decide(stirOn(), invite(
				VERSTAT_FAILED));
		assertEquals(CallBlockingConfigSample.CHALLENGE_URI, r.getRequestUri());
		assertEquals("challenge;reason=verstat-failed", r.getHeaders().get("X-Call-Screen"));
	}

	@Test
	void rapidCallerGoesToChallenge() {
		IRouterConfig config = new CallBlockingConfigSample();
		String from = "<sip:+16125550177@carrier.example.com>;tag=1";
		for (int i = 1; i <= 10; i++) {
			assertNull(decide(config, invite(from)).getRequestUri(), "call " + i);
		}
		Route eleventh = decide(config, invite(from));
		assertEquals("challenge;reason=call-rate", eleventh.getHeaders().get("X-Call-Screen"));
	}

	@Test
	void callerEqualToCalleeGoesToChallenge() {
		Route r = decide(invite("<sip:+18005550199@carrier.example.com>;tag=1"));
		assertEquals("challenge;reason=caller-is-callee", r.getHeaders().get("X-Call-Screen"));

		MemoryContext ctx = run(sample, invite("<sip:+18005550199@carrier.example.com>;tag=1"));
		assertEquals(RURI, ctx.resolve(r.getHeaders().get("X-Original-Request-URI")));
	}

	@Test
	void internationalCallerIsClear() {
		Map<String, String> h = invite("<sip:+442071234567@carrier.example.com>;tag=1");
		Route r = decide(h);
		assertNull(r.getStatusCode());
		assertEquals("clear", screen(r, run(sample, h)));
	}

	@Test
	void anonymousAndAttestationCAreWatched() {
		IRouterConfig on = stirOn();

		Map<String, String> anon = invite("\"Anonymous\" <sip:anonymous@anonymous.invalid>;tag=1");
		assertEquals("watch;reason=anonymous", screen(decide(anon), run(sample, anon)));

		Map<String, String> signed = invite("<sip:+16125550100@carrier.example.com>;tag=1");
		signed.put("Identity", passport("C", System.currentTimeMillis() / 1000));
		assertEquals("watch;reason=attestation-c", screen(decide(on, signed), run(on, signed)));

		Map<String, String> stale = invite("<sip:+16125550100@carrier.example.com>;tag=1");
		stale.put("Identity", passport("A", System.currentTimeMillis() / 1000 - 600));
		assertEquals("watch;reason=stale-passport", screen(decide(on, stale), run(on, stale)));

		Map<String, String> fresh = invite("<sip:+16125550100@carrier.example.com>;tag=1");
		fresh.put("Identity", passport("A", System.currentTimeMillis() / 1000));
		assertEquals("clear", screen(decide(on, fresh), run(on, fresh)));
		assertEquals("A", wireHeaders(decide(on, fresh), run(on, fresh)).get("X-Call-Screen-Attest"));
	}

	@Test
	void sampleSurvivesJsonRoundTrip() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		String json = mapper.writeValueAsString(sample);
		IRouterConfig loaded = mapper.readValue(json, CallBlockingConfig.class);

		assertEquals(CallBlockingConfigSample.TARPIT_URI, decide(loaded, invite("<tel:+18005550100>")).getRequestUri());
		assertEquals(CallBlockingConfigSample.REVIEW_URI,
				decide(loaded, invite("<sip:2025550150@carrier.example.com>")).getRequestUri());
		assertEquals(603, decide(loaded, invite("<sip:2025550170@carrier.example.com>")).getStatusCode());
	}

	private static String passport(String attest, long iat) {
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		String header = "{\"alg\":\"ES256\",\"ppt\":\"shaken\",\"typ\":\"passport\",\"x5u\":\"https://cert.example.com/sp.pem\"}";
		String body = "{\"attest\":\"" + attest + "\",\"dest\":{\"tn\":[\"18005550199\"]},\"iat\":" + iat
				+ ",\"orig\":{\"tn\":\"16125550100\"},\"origid\":\"de305d54-75b4-431b-adb2-eb6b9e546014\"}";
		return b64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
				+ b64.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + ".c2ln;info=<https://cert.example.com/sp.pem>";
	}
}
