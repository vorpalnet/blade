package org.vorpal.blade.framework.v3.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// A server writes its configuration leaving out what is empty or default,
/// and in the keyed form; neither is a difference. A changed value is.
class RunningConfigDifferencesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static List<String> diff(String expected, String actual) throws Exception {
		List<String> out = new ArrayList<>();
		RunningConfig.differences(MAPPER.readTree(expected.replace('\'', '"')),
				MAPPER.readTree(actual.replace('\'', '"')), "", out);
		return out;
	}

	@Test
	void emptyAndDefaultValuesMatchWhatTheServerLeavesOut() throws Exception {
		assertEquals(List.of(), diff("{'a':{'rules':{},'ops':[],'d':'','reset':false,'n':0,'x':null},'v':'1'}", "{'v':'1'}"));
	}

	@Test
	void aListFormFileMatchesTheKeyedServer() throws Exception {
		assertEquals(List.of(), diff("{'rules':[{'id':'r','method':'INVITE'}]}", "{'rules':{'r':{'method':'INVITE'}}}"));
	}

	@Test
	void listItemsWithoutIdsMatchTheNamesTheServerGaveThem() throws Exception {
		assertEquals(List.of(), diff("{'rules':[{'method':'INVITE'},{'id':'a'},{'id':'a','method':'BYE'}]}",
				"{'rules':{'rule-1':{'method':'INVITE'},'a':{},'a-2':{'method':'BYE'}}}"));
	}

	@Test
	void aChoiceInAnotherCaseIsTheSame() throws Exception {
		assertEquals(List.of(), diff("{'r':{'messageType':'Request','leg':'CALLER'}}", "{'r':{'messageType':'request','leg':'caller'}}"));
		assertEquals(List.of("r.when"), diff("{'r':{'when':'A'}}", "{'r':{'when':'a'}}"));
	}

	@Test
	void aChangedValueIsADifference() throws Exception {
		assertEquals(List.of("rules.r.method"), diff("{'rules':{'r':{'method':'BYE'}}}", "{'rules':{'r':{'method':'INVITE'}}}"));
	}
}
