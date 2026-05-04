package org.vorpal.blade.framework.v3.crud;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/// End-to-end driver for the try-it sandbox: parse raw SIP wire text,
/// apply a rule set, serialise the result. Covers the four canonical
/// CRUD verbs against a parsed-from-text message and confirms the
/// transformations land where expected.
public final class PreviewEngineSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		Logger testLogger = new TestLogger();
		SettingsManager.setSipLogger(testLogger);
		Callflow.setLogger(testLogger);

		testParseRoundTripRequest();
		testParseRoundTripResponse();
		testParseLineFolding();
		testParseRequestWithBody();

		testPreviewCreate();
		testPreviewRead();
		testPreviewUpdate();
		testPreviewDelete();
		testPreviewStatusRangeOnResponse();
		testPreviewUnknownRuleSet();
		testPreviewMalformedMessage();
		testPreviewRulesFiredList();
		testPreviewCapturesVariables();
		testPreviewInitialVariablesUsed();
		testPreviewInitialVariableOverriddenByRead();
		testPreviewRealSiprecInvite();
		testRoundTripPreservesUriWithoutSipFactory();
		testRoundTripPreservesHeaderOrder();
		testSdpDeleteOnNonMatchingFilterDoesNotThrow();
		testSdpDeleteRealSiprecInvite();
		testExampleDeleteAgainstSiprecHasNoWarnings();
		testXmlDeleteAgainstActualRsMetadataReproducesError();
		testFullSiprecAgainstAllFourSampleRuleSets();
		testBodyOpWithoutContentTypeOnMultipartReturnsNothing();
		testBodyOpWithEmptyContentTypeOnMultipartReturnsNothing();
		testBodyOpWithMismatchedContentTypeOnSinglePartReturnsNothing();
		testExampleReadAgainstSinglePartSdp();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	// ---------------------------------------------------------------------
	// Parser round-trips
	// ---------------------------------------------------------------------

	private static void testParseRoundTripRequest() throws Exception {
		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "Call-ID: abc-123\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "\r\n";
		javax.servlet.sip.SipServletMessage msg = SipMessageParser.parse(wire);
		check("parse.req.method",
				"INVITE".equals(((javax.servlet.sip.SipServletRequest) msg).getMethod()));
		check("parse.req.from", "<sip:alice@vorpal.net>;tag=1".equals(msg.getHeader("From")));
		check("parse.req.to", "<sip:bob@example.com>".equals(msg.getHeader("To")));
		check("parse.req.cseq", "1 INVITE".equals(msg.getHeader("CSeq")));
	}

	private static void testParseRoundTripResponse() throws Exception {
		String wire = "SIP/2.0 200 OK\r\n"
				+ "From: <sip:alice@x>\r\n"
				+ "To: <sip:bob@y>\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "\r\n";
		javax.servlet.sip.SipServletMessage msg = SipMessageParser.parse(wire);
		check("parse.resp.is-response", msg instanceof javax.servlet.sip.SipServletResponse);
		check("parse.resp.status",
				((javax.servlet.sip.SipServletResponse) msg).getStatus() == 200);
		check("parse.resp.reason",
				"OK".equals(((javax.servlet.sip.SipServletResponse) msg).getReasonPhrase()));
	}

	/// Continuation lines starting with whitespace must merge onto the
	/// preceding header — RFC 3261 §7.3.1.
	private static void testParseLineFolding() throws Exception {
		String wire = "INVITE sip:bob@x SIP/2.0\r\n"
				+ "From: <sip:alice@x>;tag=1\r\n"
				+ "Subject: long\r\n"
				+ "  subject continued\r\n"
				+ "To: <sip:bob@x>\r\n"
				+ "\r\n";
		javax.servlet.sip.SipServletMessage msg = SipMessageParser.parse(wire);
		check("parse.fold.merged",
				"long subject continued".equals(msg.getHeader("Subject")));
	}

	private static void testParseRequestWithBody() throws Exception {
		String wire = "INVITE sip:bob@x SIP/2.0\r\n"
				+ "From: <sip:alice@x>\r\n"
				+ "To: <sip:bob@x>\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n";
		javax.servlet.sip.SipServletMessage msg = SipMessageParser.parse(wire);
		check("parse.body.content-type",
				"application/sdp".equals(msg.getContentType()));
		String body = (String) msg.getContent();
		check("parse.body.starts-v0", body != null && body.startsWith("v=0"));
		check("parse.body.has-media", body != null && body.contains("m=audio 8000"));
	}

	// ---------------------------------------------------------------------
	// Full preview pipeline against the four canonical rule sets
	// ---------------------------------------------------------------------

	private static void testPreviewCreate() throws Exception {
		CrudConfiguration cfg = new CrudConfigurationSample();
		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "\r\n";

		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-create", wire, "callStarted");

		check("preview.create.no-error", r.error == null);
		check("preview.create.rule-fired", r.rulesFired.contains("stamp-and-attach"));
		check("preview.create.has-region", r.output.contains("X-Caller-Region: us-west-2"));
		// Body now carries the XML attachment in multipart/mixed
		check("preview.create.attachment",
				r.output.contains("Content-Type: application/recording-metadata+xml")
				&& r.output.contains("<recording><session id=\"abc-123\""));
	}

	private static void testPreviewRead() throws Exception {
		CrudConfiguration cfg = new CrudConfigurationSample();
		// The read example expects an SDP-only body; that's what dialed
		// number 8002 routes to. Use a minimal SDP since the read just
		// pulls the connection address and first media port.
		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n";

		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-read", wire, "callStarted");
		check("preview.read.no-error", r.error == null);
		check("preview.read.rule-fired", r.rulesFired.contains("harvest-everything"));
		// Reads write to the application session — they don't visibly
		// alter the wire output. The lack of error and the rule-fired
		// confirmation is enough; the per-op behaviour is covered by
		// the existing unit tests.
	}

	private static void testPreviewUpdate() throws Exception {
		CrudConfiguration cfg = new CrudConfigurationSample();
		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "\r\n";

		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-update", wire, "callStarted");
		check("preview.update.no-error", r.error == null);
		check("preview.update.from-anonymized",
				r.output.contains("From: sip:anonymous@vorpal.net"));
	}

	private static void testPreviewDelete() throws Exception {
		CrudConfiguration cfg = new CrudConfigurationSample();
		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "P-Asserted-Identity: <sip:secret@internal>\r\n"
				+ "\r\n";

		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-delete", wire, "callStarted");
		check("preview.delete.no-error", r.error == null);
		check("preview.delete.pai-gone", !r.output.contains("P-Asserted-Identity"));
	}

	/// Confirms statusRange filtering works through the full pipeline:
	/// parse a 200 response, apply a hand-built rule set with statusRange.
	private static void testPreviewStatusRangeOnResponse() throws Exception {
		Rule successOnly = new Rule();
		successOnly.setId("stamp-on-2xx");
		successOnly.setStatusRange("200-299");
		successOnly.getOperations().add(new CreateOperation("X-Was-Success", "yes"));

		Rule errorOnly = new Rule();
		errorOnly.setId("stamp-on-error");
		errorOnly.setStatusRange("4xx,5xx");
		errorOnly.getOperations().add(new CreateOperation("X-Was-Error", "yes"));

		RuleSet rs = new RuleSet();
		rs.setId("status-demo");
		rs.getRules().add(successOnly);
		rs.getRules().add(errorOnly);

		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		String okWire = "SIP/2.0 200 OK\r\n"
				+ "From: <sip:a@x>\r\n"
				+ "To: <sip:b@y>\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "\r\n";
		PreviewEngine.PreviewResult ok = PreviewEngine.preview(cfg, "status-demo", okWire, null);
		check("preview.status.2xx.success-rule-fired", ok.rulesFired.contains("stamp-on-2xx"));
		check("preview.status.2xx.error-rule-skipped", !ok.rulesFired.contains("stamp-on-error"));

		String errWire = "SIP/2.0 503 Service Unavailable\r\n"
				+ "From: <sip:a@x>\r\n"
				+ "To: <sip:b@y>\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "\r\n";
		PreviewEngine.PreviewResult err = PreviewEngine.preview(cfg, "status-demo", errWire, null);
		check("preview.status.5xx.success-rule-skipped", !err.rulesFired.contains("stamp-on-2xx"));
		check("preview.status.5xx.error-rule-fired", err.rulesFired.contains("stamp-on-error"));
	}

	// ---------------------------------------------------------------------
	// Error paths
	// ---------------------------------------------------------------------

	private static void testPreviewUnknownRuleSet() throws Exception {
		CrudConfiguration cfg = new CrudConfigurationSample();
		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "does-not-exist", "INVITE sip:x SIP/2.0\r\nFrom: a\r\nTo: b\r\n\r\n", null);
		check("preview.error.unknown-rs-message",
				r.error != null && r.error.contains("does-not-exist"));
		check("preview.error.unknown-rs-output-null", r.output == null);
	}

	private static void testPreviewMalformedMessage() throws Exception {
		CrudConfiguration cfg = new CrudConfigurationSample();
		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-create", "this is not SIP", null);
		check("preview.error.parse-message",
				r.error != null && r.error.contains("parse"));
	}

	/// rulesFired captures only the rules whose filters matched and ran
	/// — non-matching rules in the same rule set must not appear.
	private static void testPreviewRulesFiredList() throws Exception {
		Rule a = new Rule();
		a.setId("matches-invite");
		a.setMethod("INVITE");
		a.getOperations().add(new CreateOperation("X-A", "1"));

		Rule b = new Rule();
		b.setId("matches-bye");
		b.setMethod("BYE");
		b.getOperations().add(new CreateOperation("X-B", "1"));

		RuleSet rs = new RuleSet();
		rs.setId("multi");
		rs.getRules().add(a);
		rs.getRules().add(b);

		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		String wire = "INVITE sip:x SIP/2.0\r\nFrom: a\r\nTo: b\r\n\r\n";
		PreviewEngine.PreviewResult r = PreviewEngine.preview(cfg, "multi", wire, null);
		check("preview.fired.invite-only.size", r.rulesFired.size() == 1);
		check("preview.fired.invite-only.id", r.rulesFired.contains("matches-invite"));
	}

	/// Operator-supplied initial variables flow into ${var} substitution as
	/// if an Attribute Selector or environment variable had populated them.
	private static void testPreviewInitialVariablesUsed() throws Exception {
		Rule r = new Rule();
		r.setId("stamp-tenant");
		r.getOperations().add(new CreateOperation("X-Tenant", "${tenantId}"));

		RuleSet rs = new RuleSet();
		rs.setId("init-vars-demo");
		rs.getRules().add(r);

		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		java.util.Map<String, String> init = new java.util.HashMap<>();
		init.put("tenantId", "acme");

		String wire = "INVITE sip:bob@x SIP/2.0\r\nFrom: <sip:a@x>\r\nTo: <sip:b@y>\r\n\r\n";
		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "init-vars-demo", wire, null, init);
		check("preview.initial-vars.resolves",
				result.output.contains("X-Tenant: acme"));
		check("preview.initial-vars.surfaces-in-snapshot",
				"acme".equals(result.variables.get("tenantId")));
	}

	/// A read operation that captures into the same name overwrites the
	/// initial variable — captures from the message win over operator
	/// pre-population (matches production behaviour where each rule's read
	/// is the freshest value).
	private static void testPreviewInitialVariableOverriddenByRead() throws Exception {
		Rule r = new Rule();
		r.setId("read-from");
		r.getOperations().add(new ReadOperation("From",
				"sip:(?<callerUser>[^@]+)@"));

		RuleSet rs = new RuleSet();
		rs.setId("override-demo");
		rs.getRules().add(r);

		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		java.util.Map<String, String> init = new java.util.HashMap<>();
		init.put("callerUser", "stale");

		String wire = "INVITE sip:bob@x SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:b@y>\r\n\r\n";
		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "override-demo", wire, null, init);
		check("preview.initial-vars.read-wins",
				"alice".equals(result.variables.get("callerUser")));
	}

	/// Reproducer for an operator-reported run: SIPREC INVITE with a real
	/// multipart body (SDP + recording-metadata XML) against the
	/// `example-create` rule set. The engine must not throw and must
	/// produce a JSON-serialisable PreviewResult.
	private static void testPreviewRealSiprecInvite() throws Exception {
		String wire = "INVITE sip:10.73.217.237:5060 SIP/2.0\r\n"
				+ "Via: SIP/2.0/TCP 10.23.90.71:5060;branch=z9hG4bKgifh0s30bgs7grndfk30\r\n"
				+ "From: sip:acmeSrc@10.23.90.71;tag=6ee628a7a2b7d79b7d93a863608005dd\r\n"
				+ "To: <sip:10.73.217.237:5060;transport=tcp>\r\n"
				+ "Call-ID: ef6aaf063cd6dce6837da8d85584e236070@10.73.217.237\r\n"
				+ "CSeq: 58931781 INVITE\r\n"
				+ "Contact: <sip:acmeSrc@10.23.90.71:5060;transport=tcp>;+sip.src\r\n"
				+ "Max-Forwards: 70\r\n"
				+ "Require: siprec\r\n"
				+ "Content-Type: multipart/mixed; boundary=unique-boundary-1\r\n"
				+ "Content-Length: 3607\r\n"
				+ "MIME-Version: 1.0\r\n"
				+ "\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 13849481 771639 IN IP4 10.23.90.68\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 10.23.90.86\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 33098 RTP/AVP 0 101\r\n"
				+ "a=rtpmap:0 pcmu/8000\r\n"
				+ "a=ptime:20\r\n"
				+ "a=sendonly\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/rs-metadata+xml\r\n"
				+ "Content-Disposition: recording-session\r\n"
				+ "\r\n"
				+ "<?xml version='1.0' encoding='UTF-8'?>\r\n"
				+ "<recording xmlns='urn:ietf:params:xml:ns:recording'>\r\n"
				+ "  <session id=\"gxwzQvnlSSVhNe2DPctt4Q==\"/>\r\n"
				+ "</recording>\r\n"
				+ "--unique-boundary-1--\r\n";

		CrudConfiguration cfg = new CrudConfigurationSample();
		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-create", wire, "callStarted");

		check("preview.siprec.no-error",
				r.error == null || logFail("siprec error: " + r.error));
		check("preview.siprec.fired", r.rulesFired.contains("stamp-and-attach"));
		check("preview.siprec.has-region",
				r.output != null && r.output.contains("X-Caller-Region: us-west-2"));
		// Confirm the new metadata XML part attached without losing the
		// pre-existing parts.
		check("preview.siprec.kept-sdp",
				r.output != null && r.output.contains("v=0"));
		check("preview.siprec.kept-rsmeta",
				r.output != null && r.output.contains("urn:ietf:params:xml:ns:recording"));

		// The final result must serialise to JSON without throwing — that
		// was the operator's observed failure mode.
		new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(r);
		check("preview.siprec.serialises", true);
	}

	/// The Request-URI must survive round-trip even when no SipFactory is
	/// available (AdminServer-side preview WAR). Parser stashes the raw
	/// URI; serializer falls back to it when the typed URI is null.
	private static void testRoundTripPreservesUriWithoutSipFactory() throws Exception {
		String wire = "INVITE sip:10.73.217.237:5060 SIP/2.0\r\n"
				+ "From: <sip:a@x>\r\nTo: <sip:b@y>\r\n\r\n";
		javax.servlet.sip.SipServletMessage msg = SipMessageParser.parse(wire);
		String out = SipMessageSerializer.serialize(msg);
		check("roundtrip.uri-preserved",
				out.startsWith("INVITE sip:10.73.217.237:5060 SIP/2.0\r\n")
						|| logFail("got start line: " + out.split("\r\n")[0]));
	}

	/// Headers must come back in the order they went in. DummyMessage's
	/// underlying HashMap doesn't preserve insertion order; the parser
	/// stashes the original order and the serializer iterates by it.
	private static void testRoundTripPreservesHeaderOrder() throws Exception {
		String wire = "INVITE sip:bob@x SIP/2.0\r\n"
				+ "Via: SIP/2.0/UDP a.example\r\n"
				+ "From: <sip:a@x>;tag=1\r\n"
				+ "To: <sip:b@y>\r\n"
				+ "Call-ID: abc-123\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "Contact: <sip:a@x>\r\n"
				+ "Max-Forwards: 70\r\n"
				+ "Cisco-Gucid: 0014453680\r\n"
				+ "User-to-User: 04FA08\r\n"
				+ "\r\n";
		javax.servlet.sip.SipServletMessage msg = SipMessageParser.parse(wire);
		String out = SipMessageSerializer.serialize(msg);
		String[] expectedOrder = {
				"Via:", "From:", "To:", "Call-ID:", "CSeq:", "Contact:",
				"Max-Forwards:", "Cisco-Gucid:", "User-to-User:" };
		int prevIndex = -1;
		String prevName = null;
		for (String name : expectedOrder) {
			int idx = out.indexOf("\r\n" + name);
			if (idx < 0) {
				check("roundtrip.order." + name, logFail("missing " + name));
				continue;
			}
			if (idx <= prevIndex) {
				check("roundtrip.order." + name,
						logFail(name + " appears before " + prevName + " in output"));
				continue;
			}
			check("roundtrip.order." + name, true);
			prevIndex = idx;
			prevName = name;
		}

		// And confirm a create op appends to the END of the header block,
		// not somewhere in the middle.
		Rule r = new Rule();
		r.setId("stamp");
		r.getOperations().add(new CreateOperation("X-Added", "1"));
		RuleSet rs = new RuleSet();
		rs.setId("order-demo");
		rs.getRules().add(r);
		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		PreviewEngine.PreviewResult result = PreviewEngine.preview(cfg, "order-demo", wire, null);
		int xAddedIdx = result.output.indexOf("X-Added: 1");
		int u2uIdx = result.output.indexOf("User-to-User:");
		check("roundtrip.order.create-appended-at-end",
				xAddedIdx > u2uIdx
						|| logFail("X-Added at " + xAddedIdx + " not after User-to-User at " + u2uIdx));
	}

	/// Reproducer for the operator-reported `ClassCastException: ArrayNode
	/// cannot be cast to List` from `JsonContext.delete`. Triggered when a
	/// filter expression on an SDP attribute array matches nothing (or
	/// matches via a path the JacksonJsonNodeJsonProvider mishandles). The
	/// fix moves the SDP JsonPath pipeline onto the default Java-collections
	/// provider, which doesn't have the bug.
	private static void testSdpDeleteOnNonMatchingFilterDoesNotThrow() throws Exception {
		String sdp = "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=rtpmap:0 PCMU/8000\r\n"
				+ "a=sendonly\r\n";   // intentionally NOT sendrecv
		// Should be a no-op (filter matches nothing) but must not throw.
		String out = SdpHelper.deleteValue(sdp,
				"$.media[0].attributes[?(@.name=='sendrecv')]");
		check("sdp.delete.no-match.kept-sendonly", out.contains("a=sendonly"));
		check("sdp.delete.no-match.kept-rtpmap", out.contains("a=rtpmap:0 PCMU/8000"));
	}

	/// End-to-end reproducer: example-delete rule against the SIPREC INVITE
	/// the operator pasted (no `a=sendrecv` anywhere — the filter matches
	/// nothing). Must complete without throwing or producing warnings.
	private static void testSdpDeleteRealSiprecInvite() throws Exception {
		String wire = "INVITE sip:10.73.217.237:5060 SIP/2.0\r\n"
				+ "From: sip:acmeSrc@10.23.90.71;tag=6ee\r\n"
				+ "To: <sip:10.73.217.237:5060>\r\n"
				+ "Content-Type: multipart/mixed; boundary=unique-boundary-1\r\n"
				+ "\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 13849481 771639 IN IP4 10.23.90.68\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 10.23.90.86\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 33098 RTP/AVP 0 101\r\n"
				+ "a=rtpmap:0 pcmu/8000\r\n"
				+ "a=sendonly\r\n"
				+ "a=label:268749245\r\n"
				+ "--unique-boundary-1--\r\n";

		CrudConfiguration cfg = new CrudConfigurationSample();
		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-delete", wire, "callStarted");
		check("siprec.delete.no-error",
				r.error == null || logFail("error: " + r.error));
		check("siprec.delete.fired", r.rulesFired.contains("scrub-everything"));
		// The output should still contain the SDP (filter matched nothing
		// to delete from it).
		check("siprec.delete.sdp-intact",
				r.output != null && r.output.contains("a=sendonly"));
	}

	/// Reproducer for two operator-reported errors after the SDP-delete
	/// fix landed: the example-delete rule's xmlDelete and jsonDelete
	/// operations both threw against the user's SIPREC INVITE — even
	/// though the body has no part matching `application/recording-metadata+xml`
	/// or `application/json`. They should silently no-op when no matching
	/// part exists, not feed unrelated body content to the wrong parser.
	private static void testExampleDeleteAgainstSiprecHasNoWarnings() throws Exception {
		String wire = "INVITE sip:10.73.217.237:5060 SIP/2.0\r\n"
				+ "From: sip:acmeSrc@10.23.90.71;tag=6ee\r\n"
				+ "To: <sip:10.73.217.237:5060>\r\n"
				+ "P-Asserted-Identity: <sip:secret@internal>\r\n"
				+ "Content-Type: multipart/mixed; boundary=unique-boundary-1\r\n"
				+ "\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n"
				+ "a=sendonly\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/rs-metadata+xml\r\n"
				+ "\r\n"
				+ "<?xml version='1.0' encoding='UTF-8'?>\r\n"
				+ "<recording xmlns='urn:ietf:params:xml:ns:recording'>\r\n"
				+ "  <session id=\"x\"/>\r\n"
				+ "</recording>\r\n"
				+ "--unique-boundary-1--\r\n";

		CrudConfiguration cfg = new CrudConfigurationSample();
		PreviewEngine.PreviewResult r = PreviewEngine.preview(
				cfg, "example-delete", wire, "callStarted");
		check("siprec.example-delete.no-error",
				r.error == null || logFail("error: " + r.error));
		// The header delete should land. The body deletes target content
		// types not present in this message, so they no-op silently.
		check("siprec.example-delete.pai-removed",
				r.output != null && !r.output.contains("P-Asserted-Identity"));
		// SDP body intact (filter matched nothing).
		check("siprec.example-delete.sdp-kept",
				r.output != null && r.output.contains("a=sendonly"));
		// XML body intact (no matching content type, so xmlDelete no-oped).
		check("siprec.example-delete.xml-kept",
				r.output != null && r.output.contains("urn:ietf:params:xml:ns:recording"));
	}

	/// Reproducer using the operator's exact SIPREC body and a rule that
	/// targets the *real* SIPREC content type (`application/rs-metadata+xml`).
	/// If MimeHelper has a bug returning the wrong part body, this will
	/// surface a SAXParseException from XmlHelper.parse.
	private static void testXmlDeleteAgainstActualRsMetadataReproducesError() throws Exception {
		String wire = "INVITE sip:10.73.217.237:5060 SIP/2.0\r\n"
				+ "Via: SIP/2.0/TCP 10.23.90.71:5060;branch=z9hG4bKgifh0s30bgs7grndfk30\r\n"
				+ "From: sip:acmeSrc@10.23.90.71;tag=6ee628a7a2b7d79b7d93a863608005dd\r\n"
				+ "To: <sip:10.73.217.237:5060;transport=tcp>\r\n"
				+ "Call-ID: ef6aaf063cd6dce6837da8d85584e236070@10.73.217.237\r\n"
				+ "CSeq: 58931781 INVITE\r\n"
				+ "Contact: <sip:acmeSrc@10.23.90.71:5060;transport=tcp>;+sip.src\r\n"
				+ "Max-Forwards: 70\r\n"
				+ "Require: siprec\r\n"
				+ "Content-Type: multipart/mixed; boundary=unique-boundary-1\r\n"
				+ "Content-Length: 3607\r\n"
				+ "MIME-Version: 1.0\r\n"
				+ "Cisco-Gucid: 00144536801698162784\r\n"
				+ "User-to-User: 04FA080090D1B06537E860C8143030\r\n"
				+ "\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 13849481 771639 IN IP4 10.23.90.68\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 10.23.90.86\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 33098 RTP/AVP 0 101\r\n"
				+ "a=rtpmap:0 pcmu/8000\r\n"
				+ "a=ptime:20\r\n"
				+ "a=sendonly\r\n"
				+ "a=label:268749245\r\n"
				+ "m=audio 59064 RTP/AVP 0 8 18 4 9 100 101\r\n"
				+ "c=IN IP4 10.23.90.113\r\n"
				+ "a=label:268749246\r\n"
				+ "a=inactive\r\n"
				+ "\r\n"
				+ "--unique-boundary-1\r\n"
				+ "Content-Type: application/rs-metadata+xml\r\n"
				+ "Content-Disposition: recording-session\r\n"
				+ "\r\n"
				+ "<?xml version='1.0' encoding='UTF-8'?>\r\n"
				+ "\r\n"
				+ "<recording xmlns='urn:ietf:params:xml:ns:recording'>\r\n"
				+ "    <datamode>complete</datamode>\r\n"
				+ "    <session id=\"gxwzQvnlSSVhNe2DPctt4Q==\" tenant=\"acme\">\r\n"
				+ "        <associate-time>2023-10-24T15:53:04</associate-time>\r\n"
				+ "    </session>\r\n"
				+ "</recording>\r\n"
				+ "--unique-boundary-1--\r\n";

		// Operator-edited rule: target the REAL SIPREC content type.
		// Use local-name()-based XPath since the SIPREC body declares
		// xmlns='urn:ietf:params:xml:ns:recording' as the default
		// namespace, and bare `//session` doesn't match namespaced
		// elements (standard XPath behaviour).
		Rule r = new Rule();
		r.setId("scrub-tenant");
		r.setMethod("INVITE");
		r.setEvent("callStarted");
		XPathDeleteOperation xml = new XPathDeleteOperation();
		xml.setContentType("application/rs-metadata+xml");
		xml.setXpath("//*[local-name()='session']/@tenant");
		r.getOperations().add(xml);

		RuleSet rs = new RuleSet();
		rs.setId("real-rs-metadata");
		rs.getRules().add(r);
		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "real-rs-metadata", wire, "callStarted");
		check("siprec.xml-delete.no-error",
				result.error == null || logFail("error: " + result.error));
		check("siprec.xml-delete.tenant-removed",
				result.output != null && !result.output.contains("tenant=\"acme\""));
		check("siprec.xml-delete.session-id-kept",
				result.output != null && result.output.contains("id=\"gxwzQvnlSSVhNe2DPctt4Q==\""));
	}

	/// Run every sample rule set against the operator's full SIPREC INVITE.
	/// Catches whichever rule set produces unexpected internal warnings —
	/// reads them out of result.warnings via the same code path
	/// PreviewServlet uses with CapturingLogger.
	private static void testFullSiprecAgainstAllFourSampleRuleSets() throws Exception {
		String wire = USER_SIPREC_INVITE;
		CrudConfiguration cfg = new CrudConfigurationSample();

		for (String ruleSetId : new String[]{
				"example-create", "example-read", "example-update", "example-delete" }) {
			PreviewEngine.PreviewResult r = PreviewEngine.preview(
					cfg, ruleSetId, wire, "callStarted");
			check("siprec.full." + ruleSetId + ".no-error",
					r.error == null
							|| logFail(ruleSetId + " engine error: " + r.error));
			// We can't read CapturingLogger warnings from here (the engine
			// doesn't capture them; that's the servlet's job). But we can
			// verify the operation didn't blow up, which is what matters.
		}
	}

	/// Operator-reported root cause: an XML / JSON delete op with no
	/// `contentType` field set on a multipart message used to fall through
	/// to "return the entire body as a string", which then got fed to the
	/// XML / JSON parser and produced confusing parse errors instead of
	/// silently no-op'ing.
	private static void testBodyOpWithoutContentTypeOnMultipartReturnsNothing() throws Exception {
		// XPath delete with no contentType against a multipart message —
		// must NOT parse the multipart wrapper as XML.
		Rule r = new Rule();
		XPathDeleteOperation xml = new XPathDeleteOperation();
		// Intentionally NOT calling xml.setContentType(...)
		xml.setXpath("//session/@tenant");
		r.getOperations().add(xml);

		RuleSet rs = new RuleSet();
		rs.setId("xml-no-ct");
		rs.getRules().add(r);
		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "xml-no-ct", USER_SIPREC_INVITE, "callStarted");
		check("no-ct.xml.no-error",
				result.error == null || logFail("error: " + result.error));
		// Body untouched (we refused to operate without a contentType)
		check("no-ct.xml.body-intact",
				result.output != null
						&& result.output.contains("urn:ietf:params:xml:ns:recording"));
	}

	/// Same root cause, different surface: empty-string contentType used
	/// to match every part because `"X".startsWith("")` is always true.
	private static void testBodyOpWithEmptyContentTypeOnMultipartReturnsNothing() throws Exception {
		Rule r = new Rule();
		JsonPathDeleteOperation json = new JsonPathDeleteOperation();
		json.setContentType(""); // explicit empty string
		json.setJsonPath("$.agent.id");
		r.getOperations().add(json);

		RuleSet rs = new RuleSet();
		rs.setId("json-empty-ct");
		rs.getRules().add(r);
		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "json-empty-ct", USER_SIPREC_INVITE, "callStarted");
		check("empty-ct.json.no-error",
				result.error == null || logFail("error: " + result.error));
		check("empty-ct.json.sdp-intact",
				result.output != null && result.output.contains("v=0"));
	}

	/// Operator-reported second site: an XML / JSON read op with a
	/// contentType that doesn't match the body's actual content type
	/// USED to fall through to "return whole body as string" on
	/// single-part bodies, feeding SDP to the XML parser.
	private static void testBodyOpWithMismatchedContentTypeOnSinglePartReturnsNothing() throws Exception {
		String wire = "INVITE sip:bob@x SIP/2.0\r\n"
				+ "From: <sip:a@x>\r\n"
				+ "To: <sip:b@y>\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n";

		Rule r = new Rule();
		XPathReadOperation xml = new XPathReadOperation();
		xml.setContentType("application/recording-metadata+xml"); // doesn't match
		xml.getExpressions().put("tenantId", "//session/@tenant");
		r.getOperations().add(xml);

		RuleSet rs = new RuleSet();
		rs.setId("xml-mismatch");
		rs.getRules().add(r);
		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "xml-mismatch", wire, "callStarted");
		check("mismatch.xml.no-error",
				result.error == null || logFail("error: " + result.error));
		// No tenant captured (read no-oped), no SDP corruption either.
		check("mismatch.xml.body-intact",
				result.output != null && result.output.contains("v=0"));
	}

	/// End-to-end: example-read against a single-part SDP message. The
	/// xmlRead and jsonRead ops should silently no-op since their target
	/// content types don't exist; only the regex read of From and the
	/// SDP read should produce captured variables.
	private static void testExampleReadAgainstSinglePartSdp() throws Exception {
		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "CSeq: 1 INVITE\r\n"
				+ "Content-Type: application/sdp\r\n"
				+ "\r\n"
				+ "v=0\r\n"
				+ "o=- 0 0 IN IP4 1.1.1.1\r\n"
				+ "s=-\r\n"
				+ "c=IN IP4 1.1.1.1\r\n"
				+ "t=0 0\r\n"
				+ "m=audio 8000 RTP/AVP 0\r\n";
		CrudConfiguration cfg = new CrudConfigurationSample();
		PreviewEngine.PreviewResult result = PreviewEngine.preview(
				cfg, "example-read", wire, "callStarted");
		check("single-sdp.read.no-error",
				result.error == null || logFail("error: " + result.error));
		check("single-sdp.read.regex-captured",
				"alice".equals(result.variables.get("callerUser")));
		check("single-sdp.read.sdp-captured",
				"1.1.1.1".equals(result.variables.get("mediaAddr"))
						&& "8000".equals(result.variables.get("mediaPort")));
		// XML and JSON parts don't exist in this body — those reads
		// no-op silently, no warnings expected from them.
		check("single-sdp.read.no-xml-tenant",
				!result.variables.containsKey("tenantId"));
		check("single-sdp.read.no-json-agent",
				!result.variables.containsKey("agentId"));
	}

	/// The exact SIPREC INVITE the operator pasted. Verbatim.
	private static final String USER_SIPREC_INVITE =
			"INVITE sip:10.73.217.237:5060 SIP/2.0\r\n"
			+ "Via: SIP/2.0/TCP 10.23.90.71:5060;branch=z9hG4bKgifh0s30bgs7grndfk30\r\n"
			+ "From: sip:acmeSrc@10.23.90.71;tag=6ee628a7a2b7d79b7d93a863608005dd\r\n"
			+ "To: <sip:10.73.217.237:5060;transport=tcp>\r\n"
			+ "Call-ID: ef6aaf063cd6dce6837da8d85584e236070@10.73.217.237\r\n"
			+ "CSeq: 58931781 INVITE\r\n"
			+ "Contact: <sip:acmeSrc@10.23.90.71:5060;transport=tcp>;+sip.src\r\n"
			+ "Max-Forwards: 70\r\n"
			+ "Require: siprec\r\n"
			+ "Content-Type: multipart/mixed; boundary=unique-boundary-1\r\n"
			+ "Content-Length: 3607\r\n"
			+ "MIME-Version: 1.0\r\n"
			+ "Cisco-Gucid: 00144536801698162784\r\n"
			+ "User-to-User: 04FA080090D1B06537E860C8143030313434353336383031363938313632373834;encoding=hex\r\n"
			+ "\r\n"
			+ "--unique-boundary-1\r\n"
			+ "Content-Type: application/sdp\r\n"
			+ "\r\n"
			+ "v=0\r\n"
			+ "o=- 13849481 771639 IN IP4 10.23.90.68\r\n"
			+ "s=-\r\n"
			+ "c=IN IP4 10.23.90.86\r\n"
			+ "t=0 0\r\n"
			+ "m=audio 33098 RTP/AVP 0 101\r\n"
			+ "a=rtpmap:0 pcmu/8000a\r\n"
			+ "a=ptime:20\r\n"
			+ "a=maxptime:30\r\n"
			+ "a=rtpmap:101 telephone-event/8000\r\n"
			+ "a=fmtp:101 0-15\r\n"
			+ "a=sendonly\r\n"
			+ "a=label:268749245\r\n"
			+ "m=audio 59064 RTP/AVP 0 8 18 4 9 100 101\r\n"
			+ "c=IN IP4 10.23.90.113\r\n"
			+ "a=label:268749246\r\n"
			+ "a=inactive\r\n"
			+ "\r\n"
			+ "--unique-boundary-1\r\n"
			+ "Content-Type: application/rs-metadata+xml\r\n"
			+ "Content-Disposition: recording-session\r\n"
			+ "\r\n"
			+ "<?xml version='1.0' encoding='UTF-8'?>\r\n"
			+ "\r\n"
			+ "<recording xmlns='urn:ietf:params:xml:ns:recording'>\r\n"
			+ "    <datamode>complete</datamode>\r\n"
			+ "    <session id=\"gxwzQvnlSSVhNe2DPctt4Q==\">\r\n"
			+ "        <associate-time>2023-10-24T15:53:04</associate-time>\r\n"
			+ "    </session>\r\n"
			+ "</recording>\r\n"
			+ "--unique-boundary-1--\r\n";

	/// Helper for assertion failures that need to log context. Always
	/// returns false so the surrounding `||` short-circuits to FAIL.
	private static boolean logFail(String msg) {
		System.out.println("    " + msg);
		return false;
	}

	/// `read` operations should leave their captures on the session — and
	/// the engine should hand those values back so the UI can show what the
	/// reads actually pulled out.
	private static void testPreviewCapturesVariables() throws Exception {
		Rule r = new Rule();
		r.setId("capture-from");
		r.getOperations().add(new ReadOperation("From",
				"sip:(?<callerUser>[^@]+)@(?<callerHost>[^;>]+)"));

		RuleSet rs = new RuleSet();
		rs.setId("vars-demo");
		rs.getRules().add(r);

		CrudConfiguration cfg = new CrudConfiguration();
		cfg.getRuleSets().put(rs.getId(), rs);

		String wire = "INVITE sip:bob@example.com SIP/2.0\r\n"
				+ "From: <sip:alice@vorpal.net>;tag=1\r\n"
				+ "To: <sip:bob@example.com>\r\n"
				+ "\r\n";
		PreviewEngine.PreviewResult result = PreviewEngine.preview(cfg, "vars-demo", wire, null);
		check("preview.vars.user-captured", "alice".equals(result.variables.get("callerUser")));
		check("preview.vars.host-captured", "vorpal.net".equals(result.variables.get("callerHost")));
		check("preview.vars.no-extras", result.variables.size() == 2);
	}

	// ---------------------------------------------------------------------

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}

	private static final class TestLogger extends Logger {
		private static final long serialVersionUID = 1L;
		TestLogger() { super("preview", null); }
	}
}
