package org.vorpal.blade.framework.v3.crud;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.framework.v2.testing.DummyResponse;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke-test driver for the CRUD service. Exercises:
///
/// - Rule filtering (method / messageType / event)
/// - Each operation type against a DummyRequest / DummyResponse
/// - MIME multipart preservation of non-Content-Type headers
/// - Polymorphic JSON round-trip of the unified `operations` list
/// - `Rule.resetVariables` clearing read-op variables
public final class CrudSmokeTest {
	private static int passed;
	private static int failed;
	private static final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) throws Exception {
		// Operations log via SettingsManager.getSipLogger(); the production
		// LogManager wires WebLogic MBeans, so use a quiet test subclass instead.
		// Configuration.resolveVariables logs via Callflow's logger — wire it too,
		// otherwise variable substitution silently returns the unresolved input.
		Logger testLogger = new TestLogger();
		SettingsManager.setSipLogger(testLogger);
		Callflow.setLogger(testLogger);

		testRuleMatchMethod();
		testRuleMatchMessageType();
		testRuleMatchEvent();
		testRuleMatchWildcards();

		testReadAndCreate();
		testUpdateRegex();
		testDelete();
		testJsonRead();
		testJsonCreate();
		testJsonDelete();
		testXmlRead();
		testXmlUpdate();
		testSdpRoundTripPreservesBandwidth();
		testSdpUpdateAddress();

		testMimePreservesPartHeaders();
		testMimeRemovePartUnwrapsToSole();
		testCreateAttachesXmlPart();
		testCreateAttachesXmlPartToMultipart();
		testReadDeleteRestoreAcrossMessages();

		testOperationsPolymorphicRoundTrip();
		testRuleProcessOrder();
		testRuleResetVariables();

		testNowMetaVar();
		testUuidMetaVar();
		testEnvFallback();
		testIterativeSubstitution();
		testV2DelegatesToV3();
		testOriginIpFallback();
		testPeerIpPseudoHeader();
		testTransportPseudoHeader();
		testIsSecurePseudoHeader();

		testMethodOrFilter();
		testMethodNegationFilter();
		testMethodMixedFilter();
		testEventOrFilter();
		testStatusRangeExact();
		testStatusRangeRange();
		testStatusRangeShorthand();
		testStatusRangeNegation();
		testStatusRangeRequiresResponse();
		testStatusRangeMalformedIgnored();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	// --- rule filters ---

	private static void testRuleMatchMethod() throws Exception {
		Rule r = new Rule();
		r.setMethod("INVITE");
		check("rule.method.match", r.matches(invite(), "callStarted"));
		check("rule.method.miss", !r.matches(bye(), "callStarted"));
	}

	private static void testRuleMatchMessageType() throws Exception {
		Rule rq = new Rule(); rq.setMessageType("request");
		Rule rs = new Rule(); rs.setMessageType("response");
		check("rule.type.req-on-req", rq.matches(invite(), null));
		check("rule.type.req-not-on-resp", !rq.matches(response200(), null));
		check("rule.type.resp-on-resp", rs.matches(response200(), null));
		check("rule.type.resp-not-on-req", !rs.matches(invite(), null));
	}

	private static void testRuleMatchEvent() throws Exception {
		Rule r = new Rule();
		r.setEvent("callStarted");
		check("rule.event.match", r.matches(invite(), "callStarted"));
		check("rule.event.miss", !r.matches(invite(), "callConnected"));
	}

	private static void testRuleMatchWildcards() throws Exception {
		Rule r = new Rule();
		check("rule.all-null.matches-any", r.matches(invite(), "callStarted"));
		check("rule.all-null.matches-resp", r.matches(response200(), null));
	}

	// --- regex ops ---

	private static void testReadAndCreate() throws Exception {
		DummyRequest req = invite();
		req.setHeader("From", "<sip:alice@example.com>;tag=1");

		new ReadOperation("From", "sip:(?<callerUser>[^@]+)@(?<callerHost>[^;>]+)").process(req);
		check("read.user", "alice".equals(req.getApplicationSession().getAttribute("callerUser")));
		check("read.host", "example.com".equals(req.getApplicationSession().getAttribute("callerHost")));

		new CreateOperation("X-Caller-Info", "${callerUser}@${callerHost}").process(req);
		check("create.header", "alice@example.com".equals(req.getHeader("X-Caller-Info")));
	}

	private static void testUpdateRegex() throws Exception {
		DummyRequest req = invite();
		req.setHeader("From", "<sip:alice@example.com>;tag=1");
		new UpdateOperation("From",
				"sip:(?<u>[^@]+)@(?<h>[^;>]+)",
				"sip:anonymous@${h}").process(req);
		check("update.replaces",
				req.getHeader("From").contains("sip:anonymous@example.com"));
	}

	private static void testDelete() throws Exception {
		DummyRequest req = invite();
		req.setHeader("P-Asserted-Identity", "<sip:secret@internal>");
		new DeleteOperation("P-Asserted-Identity").process(req);
		check("delete.gone", req.getHeader("P-Asserted-Identity") == null);
	}

	// --- json ops ---

	private static void testJsonRead() throws Exception {
		DummyRequest req = invite();
		req.setContent("{\"agent\":{\"id\":\"A123\",\"name\":\"Carol\"}}", "application/json");

		JsonPathReadOperation read = new JsonPathReadOperation();
		read.getExpressions().put("agentId", "$.agent.id");
		read.getExpressions().put("agentName", "$.agent.name");
		read.process(req);

		check("json.read.id", "A123".equals(req.getApplicationSession().getAttribute("agentId")));
		check("json.read.name", "Carol".equals(req.getApplicationSession().getAttribute("agentName")));
	}

	private static void testJsonCreate() throws Exception {
		DummyRequest req = invite();
		req.setContent("{\"agent\":{\"id\":\"A123\"}}", "application/json");

		JsonPathCreateOperation add = new JsonPathCreateOperation("$.agent", "department", "sales");
		add.process(req);

		String body = (String) req.getContent();
		check("json.create.added", body.contains("\"department\":\"sales\""));
	}

	private static void testJsonDelete() throws Exception {
		DummyRequest req = invite();
		req.setContent("{\"agent\":{\"id\":\"A\",\"private\":\"x\"}}", "application/json");

		new JsonPathDeleteOperation("$.agent.private").process(req);

		String body = (String) req.getContent();
		check("json.delete.removed", !body.contains("private"));
		check("json.delete.kept", body.contains("\"id\":\"A\""));
	}

	// --- xml ops ---

	private static void testXmlRead() throws Exception {
		DummyRequest req = invite();
		req.setContent("<recording session-id=\"abc-123\"><meta/></recording>", "application/xml");

		XPathReadOperation read = new XPathReadOperation();
		read.getExpressions().put("sid", "//recording/@session-id");
		read.process(req);

		check("xml.read.attr", "abc-123".equals(req.getApplicationSession().getAttribute("sid")));
	}

	private static void testXmlUpdate() throws Exception {
		DummyRequest req = invite();
		req.setContent("<msg><greet>hi</greet></msg>", "application/xml");

		new XPathUpdateOperation("//greet", "hello").process(req);

		String body = (String) req.getContent();
		check("xml.update.text", body.contains("<greet>hello</greet>"));
	}

	// --- sdp ops ---

	private static void testSdpRoundTripPreservesBandwidth() throws Exception {
		DummyRequest req = invite();
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "b=AS:128\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "b=AS:64\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n";
		req.setContent(sdp, "application/sdp");

		SdpReadOperation read = new SdpReadOperation();
		read.getExpressions().put("port", "$.media[0].port");
		read.process(req);

		String body = (String) req.getContent();
		check("sdp.read.port", "8000".equals(req.getApplicationSession().getAttribute("port")));
		check("sdp.untouched.preserves-as", body.equals(sdp));
	}

	private static void testSdpUpdateAddress() throws Exception {
		DummyRequest req = invite();
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "b=AS:128\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n";
		req.setContent(sdp, "application/sdp");

		new SdpUpdateOperation("$.connection.address", "10.99.0.1").process(req);

		String body = (String) req.getContent();
		check("sdp.update.address", body.contains("c=IN IP4 10.99.0.1"));
		check("sdp.update.preserves-bandwidth", body.contains("b=AS:128"));
		check("sdp.update.preserves-rtpmap", body.contains("a=rtpmap:0 PCMU/8000"));
	}

	// --- MIME multipart ---

	private static void testMimePreservesPartHeaders() throws Exception {
		DummyRequest req = invite();
		String body = "--bnd\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "Content-Disposition: session;handling=required\r\n"
				+ "Content-ID: <sdp@call>\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "--bnd\r\n"
				+ "Content-Type: application/xml\r\n"
				+ "Content-ID: <xml@call>\r\n"
				+ "\r\n"
				+ "<meta/>\r\n"
				+ "--bnd--\r\n";
		req.setContent(body, "multipart/mixed;boundary=bnd");

		SdpUpdateOperation portUpdate = new SdpUpdateOperation();
		portUpdate.setContentType("application/sdp");
		portUpdate.setJsonPath("$.media[0].port");
		portUpdate.setValue("9000");
		portUpdate.process(req);

		String out = (String) req.getContent();
		check("mime.preserves.disposition", out.contains("Content-Disposition: session;handling=required"));
		check("mime.preserves.sdp-cid", out.contains("Content-ID: <sdp@call>"));
		check("mime.preserves.xml-cid", out.contains("Content-ID: <xml@call>"));
		check("mime.applies.to-sdp", out.contains("m=audio 9000"));
		check("mime.preserves.xml-body", out.contains("<meta/>"));
	}

	private static void testMimeRemovePartUnwrapsToSole() throws Exception {
		DummyRequest req = invite();
		String body = "--bnd\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
				+ "--bnd\r\n"
				+ "Content-Type: application/xml\r\n"
				+ "\r\n"
				+ "<meta/>\r\n"
				+ "--bnd--\r\n";
		req.setContent(body, "multipart/mixed;boundary=bnd");

		MimeHelper.removePart(req, "application/xml");

		Object content = req.getContent();
		check("mime.unwraps.has-content", content != null);
		check("mime.unwraps.no-boundary", content != null && !content.toString().contains("--bnd"));
		check("mime.unwraps.has-sdp", content != null && content.toString().startsWith("v=0"));
	}

	// --- attachments ---

	private static void testCreateAttachesXmlPart() throws Exception {
		DummyRequest req = invite();
		String sdp = "v=0\r\no=- 0 0 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\nm=audio 8000 RTP/AVP 0\r\n";
		req.setContent(sdp, "application/sdp");

		CreateOperation attach = new CreateOperation();
		attach.setAttribute("body");
		attach.setContentType("application/xml");
		attach.setValue("<recording id=\"abc\"/>");
		attach.process(req);

		String body = (String) req.getContent();
		check("attach.is-multipart", req.getContentType().startsWith("multipart/mixed"));
		check("attach.kept-sdp", body.contains("v=0\r\no=- 0 0"));
		check("attach.added-xml", body.contains("<recording id=\"abc\"/>"));
		check("attach.has-sdp-content-type", body.contains("Content-Type: application/sdp"));
		check("attach.has-xml-content-type", body.contains("Content-Type: application/xml"));
	}

	private static void testCreateAttachesXmlPartToMultipart() throws Exception {
		DummyRequest req = invite();
		String existing = "--bnd\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\no=- 0 0 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\nm=audio 8000 RTP/AVP 0\r\n"
				+ "--bnd--\r\n";
		req.setContent(existing, "multipart/mixed;boundary=bnd");

		CreateOperation attach = new CreateOperation();
		attach.setAttribute("body");
		attach.setContentType("application/xml");
		attach.setValue("<meta/>");
		attach.process(req);

		String body = (String) req.getContent();
		check("attach.multipart.kept-boundary", req.getContentType().contains("boundary=bnd"));
		check("attach.multipart.kept-sdp", body.contains("v=0"));
		check("attach.multipart.added-xml", body.contains("<meta/>"));
	}

	/// The "remove on outbound INVITE, restore on 200 OK" pattern. The
	/// session vars persist across messages in the same dialog, and
	/// `sdpCreate` parses JSON-shaped values so a saved media block round
	/// trips back into the SDP as a structured object — not a literal string.
	private static void testReadDeleteRestoreAcrossMessages() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("dialog");

		// Outbound INVITE: audio + video. We want to strip video before sending,
		// remembering it so we can splice it back into the 200 OK.
		DummyRequest invite = new DummyRequest("INVITE", "<sip:a@x>", "<sip:b@y>");
		invite.setApplicationSession(appSession);
		String inviteSdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n"
				+ "m=video 9000 RTP/AVP 96\r\n"
				+ "a=rtpmap:96 H264/90000\r\n";
		invite.setContent(inviteSdp, "application/sdp");

		SdpReadOperation save = new SdpReadOperation();
		save.setContentType("application/sdp");
		save.getExpressions().put("videoMedia", "$.media[1]");
		save.process(invite);

		new SdpDeleteOperation("$.media[1]").process(invite);
		String inviteOut = (String) invite.getContent();
		check("restore.invite-no-video", !inviteOut.contains("m=video"));
		check("restore.invite-kept-audio", inviteOut.contains("m=audio 8000"));
		check("restore.session-has-saved", appSession.getAttribute("videoMedia") != null);

		// 200 OK comes back from the far side carrying audio-only SDP. We
		// want to splice the video block we saved earlier back in. Modeled
		// here as another DummyRequest sharing the same SipApplicationSession,
		// since DummyResponse's content storage is stubbed out.
		DummyRequest ok = new DummyRequest("INVITE", "<sip:a@x>", "<sip:b@y>");
		ok.setApplicationSession(appSession);
		String okSdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 2.2.2.2\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 2.2.2.2\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 9100 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n";
		ok.setContent(okSdp, "application/sdp");

		SdpCreateOperation restore = new SdpCreateOperation();
		restore.setContentType("application/sdp");
		restore.setParentPath("$.media");
		restore.setValue("${videoMedia}");
		restore.process(ok);

		String okOut = (String) ok.getContent();
		check("restore.ok-has-audio", okOut.contains("m=audio 9100"));
		check("restore.ok-has-video-restored", okOut.contains("m=video 9000"));
		check("restore.ok-has-h264", okOut.contains("a=rtpmap:96 H264/90000"));
	}

	// --- polymorphic JSON ---

	private static void testOperationsPolymorphicRoundTrip() throws Exception {
		Rule r = new Rule();
		r.setId("demo");
		r.setMethod("INVITE");
		r.setEvent("callStarted");
		r.getOperations().add(new ReadOperation("From", "sip:(?<u>[^@]+)@"));
		r.getOperations().add(new CreateOperation("X-Caller", "${u}"));
		r.getOperations().add(new DeleteOperation("X-Internal"));

		String json = mapper.writeValueAsString(r);
		check("poly.json.has-read-type", json.contains("\"type\":\"read\""));
		check("poly.json.has-create-type", json.contains("\"type\":\"create\""));
		check("poly.json.has-delete-type", json.contains("\"type\":\"delete\""));
		check("poly.json.no-empty-arrays", !json.contains("\"messageType\""));

		Rule round = mapper.readValue(json, Rule.class);
		check("poly.round.size", round.getOperations().size() == 3);
		check("poly.round.first-is-read", round.getOperations().get(0) instanceof ReadOperation);
		check("poly.round.second-is-create", round.getOperations().get(1) instanceof CreateOperation);
	}

	// --- order matters ---

	private static void testRuleProcessOrder() throws Exception {
		DummyRequest req = invite();
		req.setHeader("From", "<sip:bob@example.com>;tag=1");

		Rule r = new Rule();
		r.getOperations().add(new ReadOperation("From", "sip:(?<u>[^@]+)@"));
		r.getOperations().add(new CreateOperation("X-Stamp", "${u}"));
		r.process(req);
		check("order.create-after-read", "bob".equals(req.getHeader("X-Stamp")));

		// Reversed order: create runs before read produces the variable
		DummyRequest req2 = invite();
		req2.setHeader("From", "<sip:bob@example.com>;tag=1");
		Rule r2 = new Rule();
		r2.getOperations().add(new CreateOperation("X-Stamp", "${u}"));
		r2.getOperations().add(new ReadOperation("From", "sip:(?<u>[^@]+)@"));
		r2.process(req2);
		check("order.create-before-read-leaves-placeholder",
				"${u}".equals(req2.getHeader("X-Stamp")));
	}

	// --- resetVariables ---

	private static void testRuleResetVariables() throws Exception {
		SipApplicationSession appSession = new DummyApplicationSession("test");
		appSession.setAttribute("u", "stale-value");

		DummyRequest req = invite();
		req.setApplicationSession(appSession);
		req.setHeader("From", "<sip:no-match-here>");

		Rule r = new Rule();
		r.setResetVariables(true);
		r.getOperations().add(new ReadOperation("From", "sip:(?<u>[^@]+)@(?<h>[^;>]+)"));
		r.getOperations().add(new CreateOperation("X-User", "${u}"));
		r.process(req);

		// `u` was wiped before the rule ran; the read didn't match (no @ in From),
		// so the create resolves with an empty `u` rather than the stale value.
		check("reset.cleared-stale", !"stale-value".equals(req.getHeader("X-User")));
	}

	// --- v3 Context: meta-vars, env fallback, iteration ---

	private static void testNowMetaVar() throws Exception {
		DummyRequest req = invite();
		new CreateOperation("X-Stamp", "${now}").process(req);
		String stamped = req.getHeader("X-Stamp");
		check("now.numeric", stamped != null && stamped.matches("\\d+"));
	}

	private static void testUuidMetaVar() throws Exception {
		DummyRequest req = invite();
		new CreateOperation("X-Trace", "${uuid}").process(req);
		String stamped = req.getHeader("X-Trace");
		check("uuid.shape",
				stamped != null && stamped.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
	}

	private static void testEnvFallback() throws Exception {
		String home = System.getenv("HOME");
		if (home == null) home = System.getProperty("user.home");
		check("env.fallback-precondition", home != null);

		DummyRequest req = invite();
		new CreateOperation("X-User-Home", "${HOME:-}${user.home}").process(req);
		// `${HOME:-}` isn't a valid form (we ignore unknown args on plain
		// names), so it lookups env "HOME" directly. `${user.home}` falls
		// back to System.getProperty.
		String stamped = req.getHeader("X-User-Home");
		check("env.user-home-resolved", stamped != null && stamped.contains(home));
	}

	/// `${a}` resolves to "Hello ${b}!", which after one pass still contains
	/// `${b}` — Context must keep iterating until stable.
	private static void testIterativeSubstitution() throws Exception {
		java.util.Map<String, String> vars = new java.util.HashMap<>();
		vars.put("greeting", "Hello ${name}!");
		vars.put("name", "World");
		String result = Context.substitute("${greeting}", vars);
		check("iterative.fully-resolved", "Hello World!".equals(result));
	}

	/// v2's Configuration.resolveVariables now delegates to Context.substitute,
	/// so v2 callers (e.g. transfer service) automatically get the new
	/// behaviour. Verify the semantics are wired through.
	private static void testV2DelegatesToV3() throws Exception {
		java.util.Map<String, String> vars = new java.util.HashMap<>();
		vars.put("user", "alice");
		String result = Configuration.resolveVariables(vars, "sip:${user}@example.com");
		check("v2.delegate.simple", "sip:alice@example.com".equals(result));

		String now = Configuration.resolveVariables(vars, "${now}");
		check("v2.delegate.now", now != null && now.matches("\\d+"));
	}

	// --- new pseudo-headers ---

	private static void testOriginIpFallback() throws Exception {
		DummyRequest req = invite();
		String origin = MessageHelper.getAttributeValue(req, "originIP", null);
		// DummyRequest has no X-Vorpal-ID, no Via stack, no remote addr;
		// the v3 Selector fallback chain bottoms out at "127.0.0.1".
		check("originIP.fallback", "127.0.0.1".equals(origin));
	}

	private static void testPeerIpPseudoHeader() throws Exception {
		DummyRequest req = invite();
		// DummyMessage.getRemoteAddr returns null; the test confirms the
		// pseudo-header is wired (no exception, no header-lookup fallthrough).
		String peer = MessageHelper.getAttributeValue(req, "peerIP", null);
		check("peerIP.no-throw", peer == null);
	}

	private static void testTransportPseudoHeader() throws Exception {
		DummyRequest req = invite();
		String transport = MessageHelper.getAttributeValue(req, "transport", null);
		check("transport.no-throw", transport == null);
	}

	private static void testIsSecurePseudoHeader() throws Exception {
		DummyRequest req = invite();
		String secure = MessageHelper.getAttributeValue(req, "isSecure", null);
		// Null transport → derives "false".
		check("isSecure.derives-false", "false".equals(secure));
	}

	// --- expanded filtering: method / event OR + negation, statusRange ---

	private static void testMethodOrFilter() throws Exception {
		Rule r = new Rule();
		r.setMethod("INVITE,REGISTER");
		check("method.or.invite-matches", r.matches(invite(), null));
		check("method.or.bye-no-match", !r.matches(bye(), null));

		DummyRequest reg = new DummyRequest("REGISTER", "<sip:a@x>", "<sip:b@y>");
		reg.setApplicationSession(new DummyApplicationSession("test"));
		check("method.or.register-matches", r.matches(reg, null));
	}

	private static void testMethodNegationFilter() throws Exception {
		Rule r = new Rule();
		r.setMethod("!BYE");
		check("method.neg.invite-matches", r.matches(invite(), null));
		check("method.neg.bye-no-match", !r.matches(bye(), null));
	}

	private static void testMethodMixedFilter() throws Exception {
		Rule r = new Rule();
		r.setMethod("INVITE,!OPTIONS");
		check("method.mixed.invite-matches", r.matches(invite(), null));
		check("method.mixed.bye-no-match", !r.matches(bye(), null));

		DummyRequest opt = new DummyRequest("OPTIONS", "<sip:a@x>", "<sip:b@y>");
		opt.setApplicationSession(new DummyApplicationSession("test"));
		check("method.mixed.options-no-match", !r.matches(opt, null));
	}

	private static void testEventOrFilter() throws Exception {
		Rule r = new Rule();
		r.setEvent("callStarted,callAnswered");
		check("event.or.started-matches", r.matches(invite(), "callStarted"));
		check("event.or.answered-matches", r.matches(invite(), "callAnswered"));
		check("event.or.connected-no-match", !r.matches(invite(), "callConnected"));
	}

	private static void testStatusRangeExact() throws Exception {
		Rule r = new Rule();
		r.setStatusRange("200");
		check("status.exact.200-match", r.matches(responseStatus(200), null));
		check("status.exact.201-no-match", !r.matches(responseStatus(201), null));
	}

	private static void testStatusRangeRange() throws Exception {
		Rule r = new Rule();
		r.setStatusRange("200-299");
		check("status.range.200", r.matches(responseStatus(200), null));
		check("status.range.250", r.matches(responseStatus(250), null));
		check("status.range.299", r.matches(responseStatus(299), null));
		check("status.range.300-no-match", !r.matches(responseStatus(300), null));
		check("status.range.199-no-match", !r.matches(responseStatus(199), null));
	}

	private static void testStatusRangeShorthand() throws Exception {
		Rule r = new Rule();
		r.setStatusRange("4xx");
		check("status.shorthand.400", r.matches(responseStatus(400), null));
		check("status.shorthand.404", r.matches(responseStatus(404), null));
		check("status.shorthand.499", r.matches(responseStatus(499), null));
		check("status.shorthand.500-no-match", !r.matches(responseStatus(500), null));

		Rule rUpper = new Rule();
		rUpper.setStatusRange("4XX");
		check("status.shorthand.case-insensitive", rUpper.matches(responseStatus(404), null));
	}

	private static void testStatusRangeNegation() throws Exception {
		Rule r = new Rule();
		r.setStatusRange("!5xx");
		check("status.neg.200-match", r.matches(responseStatus(200), null));
		check("status.neg.500-no-match", !r.matches(responseStatus(500), null));
		check("status.neg.503-no-match", !r.matches(responseStatus(503), null));
	}

	private static void testStatusRangeRequiresResponse() throws Exception {
		Rule r = new Rule();
		r.setStatusRange("200-299");
		// statusRange implicitly restricts to responses — requests fail the
		// filter regardless of value.
		check("status.requires-response", !r.matches(invite(), null));
	}

	private static void testStatusRangeMalformedIgnored() throws Exception {
		Rule r = new Rule();
		r.setStatusRange("not-a-number,200");
		// Malformed token is skipped; the second token still matches.
		check("status.malformed.200-still-matches", r.matches(responseStatus(200), null));
		check("status.malformed.500-no-match", !r.matches(responseStatus(500), null));
	}

	// --- helpers ---

	private static DummyRequest invite() throws Exception {
		DummyRequest req = new DummyRequest("INVITE", "<sip:a@x>", "<sip:b@y>");
		req.setApplicationSession(new DummyApplicationSession("test"));
		return req;
	}

	private static DummyRequest bye() throws Exception {
		DummyRequest req = new DummyRequest("BYE", "<sip:a@x>", "<sip:b@y>");
		req.setApplicationSession(new DummyApplicationSession("test"));
		return req;
	}

	private static DummyResponse response200() throws Exception {
		return new DummyResponse(invite(), 200);
	}

	private static DummyResponse responseStatus(int status) throws Exception {
		return new DummyResponse(invite(), status);
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}

	@SuppressWarnings("unused")
	private static void unused() {
		List<String> ignore = new LinkedList<>();
		Map<String, String> m = new LinkedHashMap<>();
		Arrays.asList("");
	}

	/// Logger subclass that swallows everything; used only to keep the
	/// operations' getSipLogger() calls from NPE'ing during tests.
	private static final class TestLogger extends Logger {
		private static final long serialVersionUID = 1L;
		TestLogger() { super("crud-smoke", null); }
	}
}
