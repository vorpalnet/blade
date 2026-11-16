package org.vorpal.blade.applications.callflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/// The WHERE clause the recorded-calls filter builds: every value bound, none
/// spliced into the SQL.
class RecordedCallsFilterTest {

	@Test
	void noFilterIsEveryRecord() {
		List<Object> params = new ArrayList<>();
		assertEquals(" WHERE 1 = 1", new RecordedCalls.Filter().where(params));
		assertEquals(0, params.size());
	}

	@Test
	void everyConditionIsBoundInOrder() {
		RecordedCalls.Filter f = new RecordedCalls.Filter();
		f.vorpalId = " 00c1 ";
		f.since = 1000L;
		f.before = 2000L;
		f.app = "Transfer";
		f.server = "engine0";
		f.finding = "Error";
		f.fault = "downstream";
		f.peer = "10.20.1.7";
		List<Object> params = new ArrayList<>();
		assertEquals(" WHERE 1 = 1 AND vorpal_id = ? AND traced_at >= ? AND traced_at < ?"
				+ " AND LOWER(application) = ? AND LOWER(server) = ? AND finding = ? AND fault = ?"
				+ " AND LOWER(peer) LIKE ? ESCAPE '!'", f.where(params));
		assertEquals(Arrays.asList("00C1", 1000L, 2000L, "transfer", "engine0", "error", "downstream",
				"%10.20.1.7%"), params);
	}

	@Test
	void droppedIsTheDroppedFlagNotAFinding() {
		RecordedCalls.Filter f = new RecordedCalls.Filter();
		f.finding = "dropped";
		List<Object> params = new ArrayList<>();
		assertEquals(" WHERE 1 = 1 AND dropped = 'true'", f.where(params));
		assertEquals(0, params.size());
	}

	@Test
	void aFarEndIsMatchedLiterally() {
		RecordedCalls.Filter f = new RecordedCalls.Filter();
		f.peer = "a_b%c!";
		List<Object> params = new ArrayList<>();
		f.where(params);
		assertEquals(Arrays.asList("%a!_b!%c!!%"), params);
	}

	@Test
	void aNumberIsSoughtInEveryPartyAndTheGrantsFollow() {
		RecordedCalls.Filter f = new RecordedCalls.Filter();
		f.number = "+1555";
		org.vorpal.blade.framework.v3.access.Grant g = new org.vorpal.blade.framework.v3.access.Grant();
		g.setTenants(Arrays.asList("tenant_001"));
		f.access = new TraceAccess(org.vorpal.blade.framework.v3.access.Access.of(Arrays.asList(g)), "ashburn");
		List<Object> params = new ArrayList<>();
		assertEquals(" WHERE 1 = 1 AND (LOWER(call_from) LIKE ? ESCAPE '!' OR LOWER(call_to) LIKE ? ESCAPE '!'"
				+ " OR LOWER(asserted) LIKE ? ESCAPE '!') AND ((1 = 1 AND tenant IN (?)))", f.where(params));
		assertEquals(Arrays.asList("%+1555%", "%+1555%", "%+1555%", "tenant_001"), params);
	}
}
