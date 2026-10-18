package org.vorpal.blade.overload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverloadCollectorTest {

	private final OverloadCollector collector = new OverloadCollector();

	@Test
	void reportsTheVerdictWhileItIsFresh() {
		Overload.report(true, 60_000);
		assertEquals(1, collector.getStats(null).intValue());
		Overload.report(false, 60_000);
		assertEquals(0, collector.getStats(null).intValue());
	}

	@Test
	void anExpiredVerdictReadsAsNotRefusing() {
		Overload.report(true, 1_000);
		long now = System.currentTimeMillis();
		assertTrue(Overload.refusing(now));
		assertFalse(Overload.refusing(now + 2_000));
	}

	@Test
	void eachQuestionIsRemembered() {
		Overload.report(false, 1_000);
		long before = System.currentTimeMillis();
		collector.getStats(null);
		assertTrue(Overload.consulted() >= before);
	}
}
