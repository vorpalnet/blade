package org.vorpal.blade.services.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// The queue report's low is the lowest length seen, not 0 because nothing
/// was seen yet.
class WatermarkTest {

	@Test
	void lowIsTheLowestSeen() {
		Statistics.Watermark minute = new Statistics.Watermark();
		minute.observe(4);
		minute.observe(7);
		minute.observe(5);

		assertEquals(4, minute.low());
		assertEquals(7, minute.high());
		assertTrue(minute.occupied());
	}

	@Test
	void anUnobservedPeriodReportsEmpty() {
		Statistics.Watermark idle = new Statistics.Watermark();
		assertEquals(0, idle.low());
		assertFalse(idle.occupied());
	}

	@Test
	void minutesFoldIntoTheHourAndResetDoesNotLeakZero() {
		Statistics.Watermark minute = new Statistics.Watermark();
		Statistics.Watermark hour = new Statistics.Watermark();

		minute.observe(3);
		minute.observe(6);
		hour.fold(minute);
		minute.reset();

		minute.observe(2);
		minute.observe(9);
		hour.fold(minute);

		assertEquals(2, hour.low());
		assertEquals(9, hour.high());
	}
}
