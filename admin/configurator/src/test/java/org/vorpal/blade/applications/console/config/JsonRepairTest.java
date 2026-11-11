package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// [JsonRepair]: what JavaScript did to a document in passing is undone
/// against the file; what the operator changed is kept.
class JsonRepairTest {

	private static final ObjectMapper M = new ObjectMapper();

	private static JsonNode json(String s) throws Exception {
		return M.readTree(s.replace('\'', '"'));
	}

	@Test
	void integerKeysGetTheFilesOrderBack() throws Exception {
		JsonNode old = json("{'rules':{'300':{'a':1},'lookup':{},'100':{}}}");
		// As JavaScript hands it back: index keys first, ascending.
		JsonNode saved = json("{'rules':{'100':{},'300':{'a':2},'lookup':{},'200':{}}}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"rules\":{\"300\":{\"a\":2},\"lookup\":{},\"100\":{},\"200\":{}}}", saved.toString());
	}

	@Test
	void objectsWithoutIntegerKeysKeepThePagesOrder() throws Exception {
		JsonNode saved = json("{'b':1,'a':2}");
		assertFalse(JsonRepair.repair(saved, json("{'a':2,'b':1}")));
		assertEquals("{\"b\":1,\"a\":2}", saved.toString());
	}

	@Test
	void aLongBeyondJavaScriptIsTheFilesExactly() throws Exception {
		JsonNode old = json("{'max':9223372036854775807,'ids':[9007199254740993],'small':5}");
		JsonNode saved = json("{'max':9223372036854776000,'ids':[9007199254740992],'small':6}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"max\":9223372036854775807,\"ids\":[9007199254740993],\"small\":6}", saved.toString());
	}

	@Test
	void anItemAddedAboveDoesNotShiftTheMatch() throws Exception {
		JsonNode old = json("{'list':[{'n':9223372036854775807,'t':{'300':1,'100':2}}]}");
		// The form adds an item at the top: the old item is now index 1.
		JsonNode saved = json("{'list':[{'n':1,'t':{'5':0}},{'n':9223372036854776000,'t':{'100':2,'300':1}}]}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"list\":[{\"n\":1,\"t\":{\"5\":0}},{\"n\":9223372036854775807,\"t\":{\"300\":1,\"100\":2}}]}", saved.toString());
	}

	@Test
	void listItemsAreMatchedByIdNotIndex() throws Exception {
		JsonNode old = json("{'l':[{'id':'a','m':{'1':0,'2':0}},{'id':'b','m':{'2':0,'1':0}}]}");
		// Item a removed: b is now at index 0, where a was.
		JsonNode saved = json("{'l':[{'id':'b','m':{'1':0,'2':0}}]}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"l\":[{\"id\":\"b\",\"m\":{\"2\":0,\"1\":0}}]}", saved.toString());
	}

	@Test
	void twoLongsThatRoundAlikeAreToldApartByTheirKey() throws Exception {
		JsonNode old = json("{'a':9223372036854775807,'b':9223372036854775806}");
		JsonNode saved = json("{'a':9223372036854776000,'b':9223372036854776000}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"a\":9223372036854775807,\"b\":9223372036854775806}", saved.toString());
		// A new key holding one: nothing says which.
		saved = json("{'c':9223372036854776000}");
		assertFalse(JsonRepair.repair(saved, old));
	}

	@Test
	void aRenamedKeyKeepsItsPlace() throws Exception {
		// Renamed "b" to "c"; JavaScript hands back 10 first.
		JsonNode saved = json("{'p':{'10':{},'a':{},'c':{}}}");
		assertTrue(JsonRepair.repair(saved, json("{'p':{'a':{},'b':{},'10':{}}}")));
		assertEquals("{\"p\":{\"a\":{},\"c\":{},\"10\":{}}}", saved.toString());
		// Renamed "300" to "301" among others.
		saved = json("{'p':{'100':{},'301':{},'bob':{},'lookup':{}}}");
		assertTrue(JsonRepair.repair(saved, json("{'p':{'bob':{},'300':{},'lookup':{},'100':{}}}")));
		assertEquals("{\"p\":{\"bob\":{},\"301\":{},\"lookup\":{},\"100\":{}}}", saved.toString());
	}

	@Test
	void laterSourcesSupplyWhatTheFirstLacks() throws Exception {
		// The form's file has the long and the order; the JSON tab's own text
		// (already reordered and rounded by the page) comes last.
		JsonNode file = json("{'t':9223372036854775807,'m':{'300':0,'100':0}}");
		JsonNode editor = json("{'t':9223372036854776000,'m':{'100':0,'300':0}}");
		JsonNode saved = json("{'t':9223372036854776000,'m':{'100':0,'300':0}}");
		assertTrue(JsonRepair.repair(saved, java.util.List.of(file, editor)));
		assertEquals("{\"t\":9223372036854775807,\"m\":{\"300\":0,\"100\":0}}", saved.toString());
	}

	@Test
	void renamesArePairedByValue() throws Exception {
		JsonNode old = json("{'p':{'100':{'v':'A'},'200':{'v':'B'},'300':{'v':'C'}}}");
		// 100 -> 500 and 300 -> 150; the browser hands them back ascending.
		JsonNode saved = json("{'p':{'150':{'v':'C'},'200':{'v':'B'},'500':{'v':'A'}}}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"p\":{\"500\":{\"v\":\"A\"},\"200\":{\"v\":\"B\"},\"150\":{\"v\":\"C\"}}}", saved.toString());
		// A rename and an Add in one Save: the renamed key keeps its place, the new one goes last.
		// (Here that is also the browser's order, so nothing moves.)
		saved = json("{'p':{'150':{'v':'A'},'200':{'v':'B'},'300':{'v':'C'},'400':{'v':'D'}}}");
		assertFalse(JsonRepair.repair(saved, old));
		assertEquals("{\"p\":{\"150\":{\"v\":\"A\"},\"200\":{\"v\":\"B\"},\"300\":{\"v\":\"C\"},\"400\":{\"v\":\"D\"}}}", saved.toString());
	}

	@Test
	void aRenamePlusAnAddKeepsTheRenamedPlace() throws Exception {
		JsonNode old = json("{'p':{'lookup':{'v':'A'},'200':{'v':'B'},'300':{'v':'C'}}}");
		// lookup -> 100 (A), and a new 50 (D): the browser hands back 50, 100, 200, 300.
		JsonNode saved = json("{'p':{'50':{'v':'D'},'100':{'v':'A'},'200':{'v':'B'},'300':{'v':'C'}}}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"p\":{\"100\":{\"v\":\"A\"},\"200\":{\"v\":\"B\"},\"300\":{\"v\":\"C\"},\"50\":{\"v\":\"D\"}}}", saved.toString());
	}

	@Test
	void aDeleteAndAnAddAreNotARename() throws Exception {
		JsonNode old = json("{'p':{'10':{'v':'A'},'20':{'v':'B'},'30':{'v':'C'}}}");
		JsonNode saved = json("{'p':{'20':{'v':'B'},'30':{'v':'C'},'40':{'v':'D'}}}");
		assertFalse(JsonRepair.repair(saved, old));   // already 20, 30, 40: the new one last
		saved = json("{'p':{'5':{'v':'D'},'20':{'v':'B'},'30':{'v':'C'}}}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"p\":{\"20\":{\"v\":\"B\"},\"30\":{\"v\":\"C\"},\"5\":{\"v\":\"D\"}}}", saved.toString());
	}

	@Test
	void anOrderTypedInTheJsonTabWins() throws Exception {
		JsonNode file = json("{'p':{'100':{'v':'A'},'200':{'v':'B'}}}");
		JsonNode saved = json("{'p':{'100':{'v':'A'},'200':{'v':'B'}}}");
		// The operator swapped them in the JSON tab: an order the page never writes.
		assertTrue(JsonRepair.repair(saved, java.util.List.of(file), json("{'p':{'200':{'v':'B'},'100':{'v':'A'}}}")));
		assertEquals("{\"p\":{\"200\":{\"v\":\"B\"},\"100\":{\"v\":\"A\"}}}", saved.toString());
		// The page's own (ascending) text says nothing: the file's order stands.
		JsonNode file2 = json("{'p':{'200':{'v':'B'},'100':{'v':'A'}}}");
		saved = json("{'p':{'100':{'v':'A'},'200':{'v':'B'}}}");
		assertTrue(JsonRepair.repair(saved, java.util.List.of(file2), json("{'p':{'100':{'v':'A'},'200':{'v':'B'}}}")));
		assertEquals("{\"p\":{\"200\":{\"v\":\"B\"},\"100\":{\"v\":\"A\"}}}", saved.toString());
	}

	@Test
	void theEditorsRoundedCopyDoesNotHideTheExactLong() throws Exception {
		JsonNode file = json("{'ids':[1234567890123456789]}");
		JsonNode editor = json("{'ids':[1234567890123456800]}");   // the page's own, rounded
		JsonNode saved = json("{'ids':[1234567890123456800]}");
		assertTrue(JsonRepair.repair(saved, java.util.List.of(file), editor));
		assertEquals("{\"ids\":[1234567890123456789]}", saved.toString());
	}

	@Test
	void aLongTypedInTheEditorIsKeptOverTheFilesThatRoundsAlike() throws Exception {
		JsonNode file = json("{'t':9223372036854775807}");
		JsonNode editor = json("{'t':9223372036854775806}");   // typed: not the page's rounding
		JsonNode saved = json("{'t':9223372036854776000}");
		assertTrue(JsonRepair.repair(saved, java.util.List.of(file), editor));
		assertEquals("{\"t\":9223372036854775806}", saved.toString());
	}

	@Test
	void twoLongsThatRoundAlikeInAListAreToldApartByPlace() throws Exception {
		JsonNode old = json("{'l':[9223372036854775807,9223372036854775806]}");
		JsonNode saved = json("{'l':[9223372036854776000,9223372036854776000]}");
		assertTrue(JsonRepair.repair(saved, old));
		assertEquals("{\"l\":[9223372036854775807,9223372036854775806]}", saved.toString());
	}

	@Test
	void aChangedLongIsKept() throws Exception {
		JsonNode saved = json("{'max':1000000000000000000000}");
		assertFalse(JsonRepair.repair(saved, json("{'max':9223372036854775807}")));
	}

	@Test
	void indexKeysAreJavaScriptsOnly() {
		assertTrue(JsonRepair.isIndex("0"));
		assertTrue(JsonRepair.isIndex("4294967294"));
		assertFalse(JsonRepair.isIndex("4294967295"));
		assertFalse(JsonRepair.isIndex("012"));
		assertFalse(JsonRepair.isIndex("+1"));
		assertFalse(JsonRepair.isIndex("18005551234"));
	}

	/// A new keyed map does not take the order of an unrelated one that
	/// happens to share its keys.
	@Test
	void aNewMapDoesNotBorrowAnUnrelatedOrder() throws Exception {
		com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
		com.fasterxml.jackson.databind.JsonNode source = m.readTree("{\"other\":{\"2\":\"b\",\"1\":\"a\"}}");
		com.fasterxml.jackson.databind.JsonNode saved = m.readTree("{\"other\":{\"1\":\"a\",\"2\":\"b\"},\"fresh\":{\"1\":\"x\",\"2\":\"y\"}}");
		JsonRepair.repair(saved, source);
		org.junit.jupiter.api.Assertions.assertEquals("{\"other\":{\"2\":\"b\",\"1\":\"a\"},\"fresh\":{\"1\":\"x\",\"2\":\"y\"}}", saved.toString());
	}
}
