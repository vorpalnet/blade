package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/// The range buttons a deployment configures.
class DashboardSettingsTest {

	@Test
	void theDefaultsAreAWeekAmongADayAWeekAndAMonth() {
		DashboardSettings s = new DashboardSettings();
		assertEquals(7, s.getDefaultDays());
		assertEquals(Arrays.asList(1, 7, 30), s.getRanges());
	}

	@Test
	void rangesAreSortedCleanedAndAlwaysHoldTheDefault() {
		DashboardSettings s = new DashboardSettings();
		s.setRanges(Arrays.asList(90, 0, 30, null, 1, 30, 9999));
		s.setDefaultDays(14);
		assertEquals(Arrays.asList(1, 14, 30, 90), s.getRanges(),
				"0, null and 9999 dropped, 30 once, and the default added");
		s.setDefaultDays(0);
		assertEquals(1, s.getDefaultDays(), "a range is at least a day");
	}
}
