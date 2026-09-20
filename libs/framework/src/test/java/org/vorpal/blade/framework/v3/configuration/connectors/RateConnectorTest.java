package org.vorpal.blade.framework.v3.configuration.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

import com.fasterxml.jackson.databind.ObjectMapper;

class RateConnectorTest {

	private static final long T0 = 1_700_000_000_000L;

	private static RateConnector rate(int windowSeconds, int maxKeys) {
		RateConnector r = new RateConnector();
		r.setKeyExpression("${ani}");
		r.setWindowSeconds(windowSeconds);
		r.setMaxKeys(maxKeys);
		return r;
	}

	private static MemoryContext caller(String ani) {
		MemoryContext ctx = new MemoryContext();
		ctx.put("ani", ani);
		return ctx;
	}

	@Test
	void countsCallsInTheWindow() {
		RateConnector r = rate(60, 100);
		for (int i = 1; i <= 5; i++) {
			MemoryContext ctx = caller("2025550150");
			assertEquals(i, r.record(ctx, T0 + i * 1000));
			assertEquals(Integer.toString(i), ctx.get("callRate"));
		}
		assertEquals(1, r.record(caller("8165550100"), T0 + 6000), "each number has its own count");
	}

	@Test
	void previousBucketFadesAcrossTheWindow() {
		RateConnector r = rate(60, 100);
		for (int i = 0; i < 10; i++) r.record(caller("2025550150"), T0);

		// Halfway through the next bucket, half of the previous ten still overlap.
		assertEquals(6, r.record(caller("2025550150"), T0 + 90_000));
		// Two windows on, nothing old remains.
		assertEquals(1, r.record(caller("2025550150"), T0 + 250_000));
	}

	@Test
	void fullTableDropsAgedKeysThenStopsCounting() {
		RateConnector r = rate(60, 2);
		r.record(caller("2025550150"), T0);
		r.record(caller("2025550151"), T0);

		MemoryContext third = caller("2025550152");
		assertEquals(-1, r.record(third, T0 + 1000));
		assertNull(third.get("callRate"));
		assertEquals(2, r.trackedKeys());

		assertEquals(1, r.record(caller("2025550152"), T0 + 121_000));
		assertEquals(1, r.trackedKeys());
	}

	@Test
	void unresolvedKeyIsNotCounted() {
		RateConnector r = rate(60, 100);
		MemoryContext ctx = new MemoryContext();
		assertEquals(-1, r.record(ctx, T0));
		assertNull(ctx.get("callRate"));
	}

	@Test
	void roundTripsAsRateType() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Connector c = mapper.readValue(
				"{\"type\":\"rate\",\"id\":\"rate\",\"keyExpression\":\"${ani}\",\"windowSeconds\":30}", Connector.class);
		assertTrue(c instanceof RateConnector);
		assertEquals(30, ((RateConnector) c).getWindowSeconds());
		assertEquals("callRate", ((RateConnector) c).getVariable());
		assertTrue(!mapper.writeValueAsString(c).contains("selectors"));
	}
}
