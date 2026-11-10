package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.databind.JsonNode;

/// What a caller sends lands as data: never as JSON syntax, never as a
/// reserved form's fallback; and a proxy tells the legs apart by From tag.
class ValuesAsDataTest {

	@BeforeEach
	void detached() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	@Test
	void aCallerCannotAddKeysToAJsonTemplate() {
		JsonNode n = Placeholders.json("{\"caller\":\"${u}\"}", Map.of("u", "x\",\"vip\":true,\"y\":\""));
		assertEquals(1, n.size(), n.toString());
		assertEquals("x\",\"vip\":true,\"y\":\"", n.get("caller").asText());
	}

	@Test
	void aValueMixedWithTextStaysText() {
		JsonNode n = Placeholders.json("id ${u}", Map.of("u", "{\"vip\":true}"));
		assertTrue(n.isTextual(), n.toString());
	}

	@Test
	void aLonePlaceholderRestoresWhatAReadSaved() {
		JsonNode n = Placeholders.json("${saved}", Map.of("saved", "{\"type\":\"video\",\"port\":9000}"));
		assertEquals(9000, n.get("port").asInt());
	}

	@Test
	void anObjectGoesIntoAJsonBodyAsJson() throws Exception {
		String body = "{\"a\":1}";
		SipServletRequest msg = (SipServletRequest) SipMessageParser.parse("MESSAGE sip:b@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK3\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
				+ "Call-ID: j1\r\nCSeq: 1 MESSAGE\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
				+ "\r\n\r\n" + body);
		MessageHelper.putVariable(msg.getApplicationSession(), "u", "alice");
		JsonPathUpdateOperation op = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
				"{\"type\":\"jsonUpdate\",\"jsonPath\":\"$.a\",\"value\":\"{\\\"who\\\":\\\"${u}\\\",\\\"n\\\":3}\"}", JsonPathUpdateOperation.class);
		op.process(msg);
		JsonNode out = new com.fasterxml.jackson.databind.ObjectMapper().readTree(new String(msg.getRawContent()));
		assertEquals("alice", out.get("a").get("who").asText());
		assertEquals(3, out.get("a").get("n").asInt());
	}

	@Test
	void aJsonReadOfAnObjectSavesJson() throws Exception {
		String body = "{\"m\":{\"type\":\"audio\",\"port\":49170}}";
		SipServletRequest msg = (SipServletRequest) SipMessageParser.parse("MESSAGE sip:b@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK5\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
				+ "Call-ID: j3\r\nCSeq: 1 MESSAGE\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
				+ "\r\n\r\n" + body);
		JsonPathReadOperation op = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
				"{\"type\":\"jsonRead\",\"expressions\":{\"saved\":\"$.m\"}}", JsonPathReadOperation.class);
		op.process(msg);
		JsonNode saved = new com.fasterxml.jackson.databind.ObjectMapper()
				.readTree(MessageHelper.getVariable(msg.getApplicationSession(), "saved"));
		assertEquals(49170, saved.get("port").asInt());
	}

	@Test
	void aJsonValueOutsideQuotesGoesInAsJson() {
		JsonNode n = Placeholders.json("{\"media\": ${saved}, \"n\": ${count}, \"who\": ${who}}",
				Map.of("saved", "{\"type\":\"video\"}", "count", "3", "who", "x\",\"vip\":true"));
		assertEquals("video", n.get("media").get("type").asText());
		assertEquals(3, n.get("n").asInt());
		assertEquals("x\",\"vip\":true", n.get("who").asText());
		assertEquals(3, n.size());
	}

	@Test
	void anEmptyChoiceIsNoFilter() throws Exception {
		Rule r = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"leg\":\"\",\"messageType\":\"\",\"sdp\":\" \"}", Rule.class);
		assertNull(r.getLeg());
		assertNull(r.getMessageType());
	}

	@Test
	void anUnsetReservedFormDoesNotFallBackToAVariable() {
		assertNull(Context.substituteStrict("${env:NO_SUCH_VARIABLE_XYZ}", Map.of("env", "prod"), v -> v));
		assertEquals("prod", Context.substituteStrict("${env}", Map.of("env", "prod"), v -> v));
	}

	@Test
	void onlyRuleNamedServerSettingsAreReadable() {
		System.setProperty("crud.testValue", "ok");
		System.setProperty("secret.testValue", "hidden");
		try {
			assertEquals("ok", Context.substituteStrict("${sys:crud.testValue}", Map.of(), v -> v));
			assertNull(Context.substituteStrict("${sys:secret.testValue}", Map.of(), v -> v));
		} finally {
			System.clearProperty("crud.testValue");
			System.clearProperty("secret.testValue");
		}
	}

	@Test
	void aCallerCannotRewriteAJsonPathFilter() {
		String path = "$.s[?(@.label == \"${x}\")]";
		String v = Placeholders.jsonPathValue(path, path.indexOf("${"), "x\" || @.label != \"x");
		assertEquals("x\\\" || @.label != \\\"x", v);
	}

	@Test
	void textAfterJsonIsNotJson() {
		JsonNode n = Placeholders.json("{\"flag\": ${x}}", Map.of("x", "true,\"admin\":true"));
		assertEquals(1, n.size(), n.toString());
		assertEquals("true,\"admin\":true", n.get("flag").asText());
	}

	@Test
	void aWhenCannotReadTheServer() throws Exception {
		System.setProperty("secret.when", "a");
		try {
			Rule r = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
					"{\"when\":\"${sys:secret.when} == a\"}", Rule.class);
			Rule plain = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
					"{\"when\":\"${secret.when} == a\"}", Rule.class);
			SipServletRequest invite = (SipServletRequest) SipMessageParser.parse("INVITE sip:b@x SIP/2.0\r\n"
					+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK7\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
					+ "Call-ID: w1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n");
			assertTrue(!r.matches(invite, Crud.SEND_EVENT));
			assertTrue(!plain.matches(invite, Crud.SEND_EVENT));
		} finally {
			System.clearProperty("secret.when");
		}
	}

	@Test
	void aConnectorWritesIntoTheRulesContextNotTheSession() throws Exception {
		SipServletRequest invite = (SipServletRequest) SipMessageParser.parse("INVITE sip:b@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK8\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
				+ "Call-ID: w2\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n");
		invite.getSession().setAttribute("userAgent", "caller");
		Context ctx = Context.forRules(invite, null);
		ctx.put("userAgent", "from-a-connector");
		assertEquals("caller", invite.getSession().getAttribute("userAgent"));
		assertEquals("from-a-connector", ctx.get("userAgent"));
		System.setProperty("secret.pipe", "x");
		try {
			assertEquals("${sys:secret.pipe}", ctx.resolve("${sys:secret.pipe}"));
		} finally {
			System.clearProperty("secret.pipe");
		}
	}

	@Test
	void aPipelineSelectorExpressionCannotReadServerSecrets() throws Exception {
		SipServletRequest invite = (SipServletRequest) SipMessageParser.parse("INVITE sip:b@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK9\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
				+ "Call-ID: w3\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n");
		Context ctx = Context.forRules(invite, null);
		System.setProperty("secret.pipe", "x");
		try {
			new org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector("k", "To", ".*",
					"${sys:secret.pipe}|${secret.pipe}").extract(ctx, invite);
			// Unresolved, so nothing is stored: no literal ${...} becomes data.
			assertNull(ctx.written().get("k"));
			new org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector("j", "To", "<sip:(?<u>[^@]+)@.*",
					"${u}-${now:yyyy}").extract(ctx, invite);
			org.junit.jupiter.api.Assertions.assertTrue(ctx.written().get("j").startsWith("b-"), ctx.written().toString());
		} finally {
			System.clearProperty("secret.pipe");
		}
	}

	@Test
	void aKeyedOverlayOverAListMergesByIdInsteadOfReplacingIt() throws Exception {
		com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
		JsonNode merged = org.vorpal.blade.framework.v3.configuration.ConfigFiles.merge(java.util.Arrays.asList(
				m.readTree("{\"ruleSets\":{\"lab\":{\"rules\":[{\"id\":\"a\",\"method\":\"INVITE\"},{\"id\":\"b\"}]}}}"),
				m.readTree("{\"ruleSets\":{\"lab\":{\"rules\":{\"a\":{\"enabled\":false}}}}}")), m);
		JsonNode rules = merged.path("ruleSets").path("lab").path("rules");
		assertEquals("INVITE", rules.path("a").path("method").asText(), rules.toString());
		assertEquals(false, rules.path("a").path("enabled").asBoolean(true));
		assertTrue(rules.has("b"), rules.toString());
	}

	@Test
	void anEmptyValueOutsideQuotesIsAnEmptyString() {
		JsonNode n = Placeholders.json("{\"media\": ${saved}}", Map.of("saved", ""));
		assertEquals("", n.get("media").asText());
		assertTrue(n.get("media").isTextual());
	}

	@Test
	void aJsonReadThatFindsNothingKeepsTheVariable() throws Exception {
		String body = "{\"s\":[{\"label\":\"a\",\"id\":1}]}";
		SipServletRequest msg = (SipServletRequest) SipMessageParser.parse("MESSAGE sip:b@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK6\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>\r\n"
				+ "Call-ID: j4\r\nCSeq: 1 MESSAGE\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
				+ "\r\n\r\n" + body);
		MessageHelper.putVariable(msg.getApplicationSession(), "streamId", "earlier");
		JsonPathReadOperation op = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
				"{\"type\":\"jsonRead\",\"expressions\":{\"streamId\":\"$.s[?(@.label == 'zzz')].id\"}}", JsonPathReadOperation.class);
		op.process(msg);
		assertEquals("earlier", MessageHelper.getVariable(msg.getApplicationSession(), "streamId"));
	}

	@Test
	void aStatusRangeSeesAnEarlierRulesRewrite() throws Exception {
		javax.servlet.sip.SipServletResponse r = (javax.servlet.sip.SipServletResponse) SipMessageParser.parse("SIP/2.0 486 Busy\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK10\r\nFrom: <sip:a@x>;tag=1\r\nTo: <sip:b@x>;tag=2\r\n"
				+ "Call-ID: s1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n");
		r.setAttribute(MessageHelper.PENDING_STATUS, 603);
		Rule six = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"statusRange\":\"6xx\"}", Rule.class);
		assertTrue(six.matches(r, Crud.SEND_EVENT));
	}

	@Test
	void aRewrittenXmlPartDeclaresTheEncodingItIsWrittenIn() throws Exception {
		String original = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>x</a>";
		String out = XmlHelper.serialize(original, XmlHelper.parse(original));
		assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), out);
	}

	@Test
	void aSecondConfigurationWithoutCrudLeavesTheSectionAlone() throws Exception {
		Crud crud = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"variables\":{\"v\":\"1\"}}", Crud.class);
		class WithCrud implements CrudCapable { public Crud getCrud() { return crud; } public void setCrud(Crud c) { } }
		class Without implements CrudCapable { public Crud getCrud() { return null; } public void setCrud(Crud c) { } }
		try {
			CrudHook.publish(new WithCrud());
			CrudHook.publish(new Without());
			assertEquals(crud, CrudHook.current());
			assertEquals("1", CrudHook.fileVariables().get("v"));
		} finally {
			CrudHook.reset();
		}
	}

	@Test
	void defaultsResolveWithoutARequest() {
		assertEquals("lab", new Context(null, Map.of("ruleSet", "lab")).get("ruleSet"));
	}

	@Test
	void aProxyTellsTheLegsApartByFromTag() throws Exception {
		SipServletRequest bye = (SipServletRequest) SipMessageParser.parse("BYE sip:a@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK2\r\nFrom: <sip:b@x>;tag=callee1\r\n"
				+ "To: <sip:a@x>;tag=caller1\r\nCall-ID: p1\r\nCSeq: 2 BYE\r\nContent-Length: 0\r\n\r\n");
		bye.getSession().setAttribute("userAgent", "caller");
		bye.getApplicationSession().setAttribute("isProxy", Boolean.TRUE); // as the framework marks a proxy
		bye.getSession().setAttribute(Rule.CALLER_TAG, "caller1");
		assertEquals("callee", Rule.legOf(bye));
	}
}
