package org.vorpal.blade.services.crud;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummyRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Four canonical examples that every CRUD user should be able to read top
/// to bottom and follow:
///
/// 1. **Create** — add SIP headers and attach an XML metadata part to the body
/// 2. **Read**   — extract values via regex / XPath / JsonPath / SDP into
///                 session variables
/// 3. **Update** — modify values across all four addressing modes
/// 4. **Delete** — strip values across all four addressing modes
///
/// Each example builds a [RuleSet] in code, runs it against a representative
/// message, asserts the result, and prints the rule set's JSON so a human
/// can eyeball that the configuration we'd ask an operator to write is
/// genuinely readable.
public final class ExamplesSmokeTest {
	private static int passed;
	private static int failed;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(String[] args) throws Exception {
		Logger testLogger = new TestLogger();
		SettingsManager.setSipLogger(testLogger);
		Callflow.setLogger(testLogger);

		runExample("CREATE — stamp headers + attach XML metadata", exampleCreate());
		runExample("READ   — harvest values from headers, XML, JSON, SDP", exampleRead());
		runExample("UPDATE — rewrite values across all four modes",       exampleUpdate());
		runExample("DELETE — scrub values across all four modes",         exampleDelete());

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	// =========================================================================
	// Example 1: CREATE
	// =========================================================================

	private static Example exampleCreate() throws Exception {
		// Rule set: tag this call with where we are and an X-Trace-Id, then
		// staple a small recording-metadata XML to the body.
		RuleSet rs = new RuleSet();
		rs.setId("example-create");
		rs.setDescription("Stamp routing headers and attach recording metadata XML");

		Rule r = new Rule();
		r.setId("stamp-and-attach");
		r.setMethod("INVITE");
		r.setEvent("callStarted");
		r.getOperations().add(new CreateOperation("X-Caller-Region", "us-west-2"));
		r.getOperations().add(new CreateOperation("X-Trace-Id", "trace-${userPart}"));
		CreateOperation attach = new CreateOperation();
		attach.setAttribute("body");
		attach.setContentType("application/recording-metadata+xml");
		attach.setValue("<recording><session id=\"abc-123\" tenant=\"acme\"/></recording>");
		r.getOperations().add(attach);
		rs.getRules().add(r);

		// Starting message: outbound INVITE with SDP, plus a session var that
		// Trace-Id will pick up via ${userPart}.
		DummyRequest msg = new DummyRequest("INVITE",
				"<sip:alice@vorpal.net>;tag=1",
				"<sip:bob@example.com>");
		SipApplicationSession appSession = new DummyApplicationSession("dialog");
		appSession.setAttribute("userPart", "alice");
		msg.setApplicationSession(appSession);
		msg.setContent("v=0\r\no=- 0 0 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\nm=audio 8000 RTP/AVP 0\r\n",
				"application/sdp");

		Example ex = new Example("create", rs, msg);
		ex.assertion = (req) -> {
			check("create.region", "us-west-2".equals(req.getHeader("X-Caller-Region")));
			check("create.trace", "trace-alice".equals(req.getHeader("X-Trace-Id")));
			check("create.body-multipart",
					req.getContentType().startsWith("multipart/mixed"));
			String body = (String) req.getContent();
			check("create.body-keeps-sdp", body.contains("v=0\r\no=- 0 0"));
			check("create.body-has-xml",
					body.contains("<recording><session id=\"abc-123\" tenant=\"acme\"/></recording>"));
			check("create.body-has-xml-content-type",
					body.contains("Content-Type: application/recording-metadata+xml"));
		};
		return ex;
	}

	// =========================================================================
	// Example 2: READ
	// =========================================================================

	private static Example exampleRead() throws Exception {
		RuleSet rs = new RuleSet();
		rs.setId("example-read");
		rs.setDescription("Capture values from headers, XML, JSON, and SDP into session variables");

		Rule r = new Rule();
		r.setId("harvest-everything");
		r.setMethod("INVITE");
		r.setEvent("callStarted");

		// Regex against the From header.
		r.getOperations().add(new ReadOperation("From",
				"sip:(?<callerUser>[^@]+)@(?<callerHost>[^;>]+)"));

		// XPath against the XML part.
		XPathReadOperation xml = new XPathReadOperation();
		xml.setContentType("application/recording-metadata+xml");
		xml.getExpressions().put("tenantId", "//session/@tenant");
		xml.getExpressions().put("sessionId", "//session/@id");
		r.getOperations().add(xml);

		// JsonPath against the JSON part.
		JsonPathReadOperation json = new JsonPathReadOperation();
		json.setContentType("application/json");
		json.getExpressions().put("agentId", "$.agent.id");
		r.getOperations().add(json);

		// SDP read.
		SdpReadOperation sdp = new SdpReadOperation();
		sdp.setContentType("application/sdp");
		sdp.getExpressions().put("mediaAddr", "$.connection.address");
		sdp.getExpressions().put("mediaPort", "$.media[0].port");
		r.getOperations().add(sdp);

		rs.getRules().add(r);

		DummyRequest msg = mixedBodyRequest();
		Example ex = new Example("read", rs, msg);
		ex.assertion = (req) -> {
			SipApplicationSession s = req.getApplicationSession();
			check("read.regex-user", "alice".equals(s.getAttribute("callerUser")));
			check("read.regex-host", "vorpal.net".equals(s.getAttribute("callerHost")));
			check("read.xml-tenant", "acme".equals(s.getAttribute("tenantId")));
			check("read.xml-session", "abc-123".equals(s.getAttribute("sessionId")));
			check("read.json-agent", "A-42".equals(s.getAttribute("agentId")));
			check("read.sdp-port", "8000".equals(s.getAttribute("mediaPort")));
			check("read.sdp-addr", "1.1.1.1".equals(s.getAttribute("mediaAddr")));
		};
		return ex;
	}

	// =========================================================================
	// Example 3: UPDATE
	// =========================================================================

	private static Example exampleUpdate() throws Exception {
		RuleSet rs = new RuleSet();
		rs.setId("example-update");
		rs.setDescription("Anonymize the From header and rewrite values across XML, JSON, and SDP");

		Rule r = new Rule();
		r.setId("rewrite-everything");
		r.setMethod("INVITE");
		r.setEvent("callStarted");

		r.getOperations().add(new UpdateOperation("From",
				"sip:(?<u>[^@]+)@(?<h>[^;>]+)",
				"sip:anonymous@${h}"));

		XPathUpdateOperation xml = new XPathUpdateOperation();
		xml.setContentType("application/recording-metadata+xml");
		xml.setXpath("//session/@tenant");
		xml.setValue("redacted");
		r.getOperations().add(xml);

		JsonPathUpdateOperation json = new JsonPathUpdateOperation();
		json.setContentType("application/json");
		json.setJsonPath("$.agent.id");
		json.setValue("MASKED");
		r.getOperations().add(json);

		SdpUpdateOperation sdp = new SdpUpdateOperation();
		sdp.setContentType("application/sdp");
		sdp.setJsonPath("$.connection.address");
		sdp.setValue("10.99.0.1");
		r.getOperations().add(sdp);

		rs.getRules().add(r);

		DummyRequest msg = mixedBodyRequest();
		Example ex = new Example("update", rs, msg);
		ex.assertion = (req) -> {
			check("update.from-anonymous",
					req.getHeader("From").contains("sip:anonymous@vorpal.net"));
			String body = (String) req.getContent();
			check("update.xml-tenant", body.contains("tenant=\"redacted\""));
			check("update.json-agent", body.contains("\"id\":\"MASKED\""));
			check("update.sdp-addr", body.contains("c=IN IP4 10.99.0.1"));
			check("update.sdp-preserves-rtpmap", body.contains("a=rtpmap:0 PCMU/8000"));
		};
		return ex;
	}

	// =========================================================================
	// Example 4: DELETE
	// =========================================================================

	private static Example exampleDelete() throws Exception {
		RuleSet rs = new RuleSet();
		rs.setId("example-delete");
		rs.setDescription("Strip private values across headers, XML, JSON, and SDP");

		Rule r = new Rule();
		r.setId("scrub-everything");
		r.setMethod("INVITE");
		r.setEvent("callStarted");

		r.getOperations().add(new DeleteOperation("P-Asserted-Identity"));

		XPathDeleteOperation xml = new XPathDeleteOperation();
		xml.setContentType("application/recording-metadata+xml");
		xml.setXpath("//session/@tenant");
		r.getOperations().add(xml);

		JsonPathDeleteOperation json = new JsonPathDeleteOperation();
		json.setContentType("application/json");
		json.setJsonPath("$.agent.id");
		r.getOperations().add(json);

		SdpDeleteOperation sdp = new SdpDeleteOperation();
		sdp.setContentType("application/sdp");
		sdp.setJsonPath("$.media[0].attributes[?(@.name=='sendrecv')]");
		r.getOperations().add(sdp);

		rs.getRules().add(r);

		DummyRequest msg = mixedBodyRequest();
		msg.setHeader("P-Asserted-Identity", "<sip:secret@internal>");
		Example ex = new Example("delete", rs, msg);
		ex.assertion = (req) -> {
			check("delete.header-gone", req.getHeader("P-Asserted-Identity") == null);
			String body = (String) req.getContent();
			check("delete.xml-tenant-gone", !body.contains("tenant="));
			check("delete.json-agent-id-gone", !body.contains("\"id\":\"A-42\""));
			check("delete.sdp-attr-gone", !body.contains("a=sendrecv"));
			check("delete.sdp-preserves-rtpmap", body.contains("a=rtpmap:0 PCMU/8000"));
		};
		return ex;
	}

	// =========================================================================
	// Sample message used by READ / UPDATE / DELETE
	// =========================================================================

	private static DummyRequest mixedBodyRequest() throws Exception {
		String boundary = "demo-bnd";
		String body = "--" + boundary + "\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n"
				+ "a=sendrecv\r\n"
				+ "--" + boundary + "\r\n"
				+ "Content-Type: application/recording-metadata+xml\r\n"
				+ "\r\n"
				+ "<recording><session id=\"abc-123\" tenant=\"acme\"/></recording>\r\n"
				+ "--" + boundary + "\r\n"
				+ "Content-Type: application/json\r\n"
				+ "\r\n"
				+ "{\"agent\":{\"id\":\"A-42\",\"name\":\"Carol\"}}\r\n"
				+ "--" + boundary + "--\r\n";

		DummyRequest msg = new DummyRequest("INVITE",
				"<sip:alice@vorpal.net>;tag=1",
				"<sip:bob@example.com>");
		msg.setApplicationSession(new DummyApplicationSession("dialog"));
		msg.setContent(body, "multipart/mixed;boundary=" + boundary);
		return msg;
	}

	// =========================================================================
	// Runner & helpers
	// =========================================================================

	@FunctionalInterface
	private interface MessageAssertion {
		void check(DummyRequest msg) throws Exception;
	}

	private static final class Example {
		final String name;
		final RuleSet ruleSet;
		final DummyRequest msg;
		MessageAssertion assertion;

		Example(String name, RuleSet ruleSet, DummyRequest msg) {
			this.name = name;
			this.ruleSet = ruleSet;
			this.msg = msg;
		}
	}

	private static void runExample(String label, Example ex) throws Exception {
		System.out.println();
		System.out.println("========================================================================");
		System.out.println("  " + label);
		System.out.println("========================================================================");
		System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ex.ruleSet));
		System.out.println();
		ex.ruleSet.applyRules(ex.msg, "callStarted");
		ex.assertion.check(ex.msg);
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}

	private static final class TestLogger extends Logger {
		private static final long serialVersionUID = 1L;
		TestLogger() { super("examples", null); }
	}
}
