package org.vorpal.blade.services.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverloadVerdictTest {

	@Test
	void refusesAtTheLineAndResumesOnlyBelowHalfOfIt() {
		OverloadVerdict v = new OverloadVerdict();
		assertFalse(v.sample(90, 100));
		assertTrue(v.sample(100, 100));
		assertTrue(v.sample(60, 100), "between the lines: still refusing");
		assertTrue(v.sample(50, 100));
		assertFalse(v.sample(40, 100));
		assertFalse(v.sample(90, 100), "between the lines from below: still taking calls");
	}

	@Test
	void noReadingTakesCallsAndZeroTurnsItOff() {
		OverloadVerdict v = new OverloadVerdict();
		assertTrue(v.sample(150, 100));
		assertFalse(v.sample(-1, 100), "an idle engine is not refusing");
		assertTrue(v.sample(150, 100));
		assertFalse(v.sample(150, 0));
	}

	@Test
	void theLatencyIsTheNinetiethPercentileOfTheLastSecond() {
		EngineLatency l = new EngineLatency();
		String[] zero = counts(new int[21]);
		assertEquals(-1, l.sample(zero), "the first reading is only a baseline");
		assertEquals(-1, l.sample(zero), "nothing finished");
		int[] c = new int[21];
		c[0] = 90; // 90 messages under 10 ms
		c[12] = 10; // 10 at 120-129 ms
		assertEquals(0, l.sample(counts(c)), "90 of 100 under 10 ms");
		c[12] = 11;
		assertEquals(120, l.sample(counts(c)), "one more slow one tips the 90th percentile");
		// Quiet readings push the busy second out of the window.
		for (int i = 0; i < EngineLatency.WINDOW; i++) {
			l.sample(counts(c));
		}
		assertEquals(-1, l.sample(counts(c)));
	}

	@Test
	void theLastCountIsTwoHundredOrMoreAndCountersMayWrap() {
		int[] c = new int[21];
		c[20] = 5;
		assertEquals(200, EngineLatency.percentile(c, 90));
		EngineLatency l = new EngineLatency();
		int[] before = new int[21];
		before[3] = Integer.MAX_VALUE;
		l.sample(counts(before));
		int[] after = before.clone();
		after[3] = Integer.MIN_VALUE + 9; // wrapped: ten more
		assertEquals(30, l.sample(counts(after)));
	}

	@Test
	void pingsTurnAwayAtHalfTheRefuseLine() {
		assertEquals(null, OverloadMonitor.pingBusy(40, 100));
		assertEquals("latency 50 ms", OverloadMonitor.pingBusy(50, 100));
		assertEquals(null, OverloadMonitor.pingBusy(-1, 100));
		assertEquals(null, OverloadMonitor.pingBusy(150, 0));
	}

	@Test
	void aStallIsMessagesWaitingWithNoneFinished() {
		assertTrue(OverloadMonitor.stalled(-1, true, 12));
		assertFalse(OverloadMonitor.stalled(-1, true, 0), "idle");
		assertFalse(OverloadMonitor.stalled(-1, false, 12), "unreadable: never guess");
		assertFalse(OverloadMonitor.stalled(40, true, 12));
	}

	@Test
	void aWarningRaisesAtItsLineAndClearsWellBelowIt() {
		assertEquals(1, OverloadMonitor.warningChange(false, 25, 25));
		assertEquals(0, OverloadMonitor.warningChange(true, 22, 25));
		assertEquals(-1, OverloadMonitor.warningChange(true, 19, 25));
		assertEquals(0, OverloadMonitor.warningChange(false, -1, 25));
		assertEquals(0, OverloadMonitor.warningChange(true, -1, 25));
		assertEquals(-1, OverloadMonitor.warningChange(true, 50, 0));
		assertEquals(0, OverloadMonitor.warningChange(false, 50, 0));
	}

	@Test
	void growthIsMeasuredOverTheTrailingWindow() {
		Strain.Totals t = new Strain.Totals();
		assertEquals(-1, t.growth(0, 100, 10_000));
		assertEquals(50, t.growth(5_000, 150, 10_000));
		assertEquals(5_000, t.span());
		// A late reading keeps the last point before the window as its
		// baseline, and the span says how long that really was.
		assertEquals(350, t.growth(20_000, 500, 10_000));
		assertEquals(15_000, t.span());
		assertEquals(200, t.growth(25_000, 700, 10_000));
		assertEquals(5_000, t.span());
		assertEquals(25, Strain.percentOf(2_500, 10_000));
		assertEquals(-1, Strain.percentOf(-1, 10_000));
	}

	@Test
	void onlyTheOldGenerationCounts() {
		assertTrue(Strain.isOld("G1 Old Gen"));
		assertTrue(Strain.isOld("Tenured Gen"));
		assertFalse(Strain.isOld("G1 Eden Space"));
	}

	private static String[] counts(int[] c) {
		String[] s = new String[c.length];
		for (int i = 0; i < c.length; i++) {
			s[i] = Integer.toString(c[i]);
		}
		return s;
	}
}
