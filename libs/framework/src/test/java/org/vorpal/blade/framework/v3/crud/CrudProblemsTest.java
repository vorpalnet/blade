package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Save refuses what would fail only once a call reaches it: a `when` that
/// does not parse, a status range that is not one, a regex, XPath or JsonPath
/// that does not compile, a default rule set that does not exist. Each
/// problem says where it is.
class CrudProblemsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static List<String> problems(String json) throws Exception {
		Crud crud = MAPPER.readValue(json.replace('\'', '"'), Crud.class);
		return Crud.problems(crud.getRuleSets(), crud.getDefaultRuleSet());
	}

	private static String rule(String body) {
		return "{'ruleSets':{'rs':{'id':'rs','rules':[{'id':'r'," + body + "}]}}}";
	}

	@Test
	void aSoundConfigHasNone() throws Exception {
		assertEquals(List.of(), problems(rule("'when':'${tier} == gold','statusRange':'200-299,4xx,!486',"
				+ "'leg':'callee','sdp':'offer','operations':["
				+ "{'type':'delete','attribute':'P-Asserted-Identity','pattern':'^<tel:${n}>$'},"
				+ "{'type':'xmlDelete','contentType':'application/rs-metadata+xml','xpath':'//*[local-name()=\\\"stream\\\"][@id = ${id}]'},"
				+ "{'type':'sdpDelete','jsonPath':'$.media[?(@.label == \\'${keep}\\')]'}]")));
	}

	@Test
	void aWhenThatDoesNotParse() throws Exception {
		List<String> p = problems(rule("'when':'${tier} == == gold'"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).startsWith("rule set rs, rule r: when: "), p.get(0));
	}

	@Test
	void aStatusRangeThatIsNotOne() throws Exception {
		List<String> p = problems(rule("'statusRange':'4xx,busy'"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("statusRange: \"busy\""), p.get(0));
	}

	@Test
	void aBrokenRegexWithAPlaceholder() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'delete','attribute':'X-A','pattern':'(${n}'}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).startsWith("rule set rs, rule r: operation 1: "), p.get(0));
	}

	@Test
	void aBrokenXPathNamesItsVariable() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'xmlRead','contentType':'application/xml',"
				+ "'expressions':{'streamId':'//stream[@label='}}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("operation 1: streamId: "), p.get(0));
	}

	@Test
	void aBrokenJsonPath() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'jsonDelete','jsonPath':'$.a[?(@.b == '}]"));
		assertEquals(1, p.size(), p.toString());
	}

	@Test
	void aDefaultRuleSetThatDoesNotExist() throws Exception {
		List<String> p = problems("{'defaultRuleSet':'nope','ruleSets':{}}");
		assertEquals(List.of("defaultRuleSet: no rule set is named \"nope\""), p);
	}

	@Test
	void choicesLoadInAnyCase() throws Exception {
		assertEquals(List.of(), problems(rule("'messageType':'Request','leg':'CALLER','sdp':'Offer'")));
	}

	@Test
	void aRejectStatusThatIsNotAFailure() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'reject','status':200}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("not 400-699"), p.get(0));
		// A redirect needs a Contact a refusal cannot carry.
		assertEquals(1, problems(rule("'operations':[{'type':'reject','status':302}]")).size());
	}

	@Test
	void aStatusChangeInARuleThatOnlyReceives() throws Exception {
		List<String> p = problems(rule("'event':'receive','operations':[{'type':'update','attribute':'status','pattern':'486','replacement':'480'}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("takes effect on a response the application sends"), p.get(0));
		assertEquals(List.of(), problems(rule("'event':'receive,send','operations':[{'type':'update','attribute':'status','pattern':'486','replacement':'480'}]")));
	}

	@Test
	void anUpdateWithoutAReplacementNeedsEveryMatch() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'update','attribute':'To','pattern':'x'}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("replacement: required"), p.get(0));
		assertEquals(List.of(), problems(rule("'operations':[{'type':'update','attribute':'To','pattern':'x','everyMatch':true}]")));
	}

	@Test
	void anOperationNeedsAnAttribute() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'create','value':'x'}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("attribute: required"), p.get(0));
	}

	@Test
	void aTrailingCommaInStatusRangeIsFine() throws Exception {
		assertEquals(List.of(), problems(rule("'statusRange':'4xx, 5xx,'")));
	}

	@Test
	void aRuleIdOfDigitsOnly() throws Exception {
		List<String> p = problems("{'ruleSets':{'rs':{'rules':{'2':{},'strip':{}}}}}");
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("digits only"), p.get(0));
	}

	@Test
	void aDefaultRuleSetARemovalTookAway() throws Exception {
		java.util.Map<String, RuleSet> sets = new java.util.LinkedHashMap<>();
		sets.put("lab", null);
		assertEquals(List.of("defaultRuleSet: no rule set is named \"lab\""), Crud.problems(sets, "lab"));
	}

	@Test
	void aReadOrUpdateNeedsAPattern() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'update','attribute':'X-A','replacement':'gold'},{'type':'read','attribute':'X-A'}]"));
		assertEquals(2, p.size(), p.toString());
		assertTrue(p.get(0).contains("pattern: required") && p.get(1).contains("pattern: required"), p.toString());
	}

	@Test
	void anEmptyOperation() throws Exception {
		List<String> p = problems(rule("'operations':[null]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("empty (null)"), p.get(0));
	}

	@Test
	void aFilterOutsideItsChoicesTurnsOnlyThatRuleOff() throws Exception {
		List<String> p = problems("{'ruleSets':{'rs':{'rules':{'bad':{'leg':'both'},'good':{'leg':'caller'}}}}}");
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).startsWith("rule set rs, rule bad: leg: \"both\" is not one of its choices"), p.get(0));
		Crud c = MAPPER.readValue("{'ruleSets':{'rs':{'rules':{'bad':{'sdp':'both'}}}}}".replace('\'', '"'), Crud.class);
		javax.servlet.sip.SipServletRequest invite = (javax.servlet.sip.SipServletRequest) SipMessageParser.parse(
				"INVITE sip:b@x SIP/2.0\r\nVia: SIP/2.0/UDP 1.1.1.1;branch=z9hG4bK9\r\nFrom: <sip:a@x>;tag=1\r\n"
						+ "To: <sip:b@x>\r\nCall-ID: e1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n");
		assertTrue(!c.getRuleSets().get("rs").getRules().get("bad").matches(invite, Crud.SEND_EVENT));
	}

	@Test
	void aJsonPathPlaceholderOutsideQuotes() throws Exception {
		List<String> p = problems(rule("'operations':[{'type':'jsonDelete','jsonPath':'$.s[?(@.label == ${x})]'}]"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("outside quotes"), p.get(0));
	}
}
