package org.vorpal.blade.admin.crud;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.crud.CrudConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// A saved test passes while the rules give its result, and says what
/// differs once they do not; the file's `variables` start each run.
class CrudTestsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String INVITE = "INVITE sip:bob@example.com SIP/2.0\\r\\n"
			+ "Via: SIP/2.0/UDP 10.0.0.1;branch=z9hG4bK1\\r\\nFrom: <sip:alice@example.com>;tag=1\\r\\n"
			+ "To: <sip:bob@example.com>\\r\\nCall-ID: t1\\r\\nCSeq: 1 INVITE\\r\\nMax-Forwards: 70\\r\\n"
			+ "Content-Length: 0\\r\\n\\r\\n";

	@BeforeEach
	void detached() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	private static CrudConfiguration config() throws Exception {
		return MAPPER.readValue(("{'variables':{'tier':'gold'},'ruleSets':{'rs':{'rules':{"
				+ "'refuse':{'when':'${tier} == gold','operations':[{'type':'reject','status':603,'reasonPhrase':'No'}]}}}}}")
				.replace('\'', '"'), CrudConfiguration.class);
	}

	private static JsonNode test(String expect) throws Exception {
		return MAPPER.readTree(("{'ruleSet':'rs','event':'callStarted','message':'" + INVITE + "','expect':" + expect + "}")
				.replace('\'', '"'));
	}

	@Test
	void aTestThatStillHoldsPasses() throws Exception {
		assertNull(CrudTests.failure(config(), test("{'refused':'603 No'}")));
	}

	@Test
	void onlyTheFilesOwnTestsRun() throws Exception {
		com.fasterxml.jackson.databind.node.ObjectNode tests = MAPPER.createObjectNode();
		tests.set("domain one", test("{}"));
		com.fasterxml.jackson.databind.node.ObjectNode server = (com.fasterxml.jackson.databind.node.ObjectNode) test("{}");
		server.put("file", "server/engine0");
		tests.set("server one", server);
		assertTrue(CrudTests.run(config(), tests, "domain").size() == 1);
		assertTrue(CrudTests.failures(config(), tests, "server/engine0").get(0).startsWith("test \"server one\""));
	}

	@Test
	void aChangedResultIsReported() throws Exception {
		String why = CrudTests.failure(config(), test("{}"));
		assertTrue(why != null && why.contains("expected no refusal, got refused 603 No"), why);
	}
}
