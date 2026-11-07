package org.vorpal.blade.framework.v3.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/// `${now:FORMAT@ZONE}`: the current time in a named zone, for rules about local business hours.
class NowInZoneTest {

	@Test
	void theHourFollowsTheNamedZone() {
		ZoneId newYork = ZoneId.of("America/New_York");
		int before = ZonedDateTime.now(newYork).getHour();
		String hour = new Context(null).resolve("${now:H@America/New_York}");
		int after = ZonedDateTime.now(newYork).getHour();
		// Read the clock on both sides, in case the hour turns during the test.
		assertTrue(hour.equals(String.valueOf(before)) || hour.equals(String.valueOf(after)), hour);
	}

	@Test
	void aPatternWithoutAZoneIsUnchanged() {
		Context context = new Context(null);
		assertEquals(String.valueOf(ZonedDateTime.now(ZoneId.of("UTC")).getYear()), context.resolve("${now:yyyy}"));
	}

	@Test
	void anUnknownZoneLeavesThePlaceholder() {
		assertEquals("${now:H@Nowhere/Atlantis}", new Context(null).resolve("${now:H@Nowhere/Atlantis}"));
	}
}
