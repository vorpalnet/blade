package org.vorpal.blade.services.analytics.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

/// Pins the key algorithm, because it is a wire contract and not an
/// implementation detail.
///
/// Two nodes writing the same call must compute the same id, and a node running
/// last year's release must agree with one running this year's. Nothing in the
/// type system enforces that — a "harmless" change to the digest, the encoding
/// or the framing would compile, pass every other test, and quietly make every
/// stored row unreachable while writing duplicates alongside it. These
/// assertions are the thing that fails instead.
///
/// **If one of these breaks, the fix is almost never to update the expected
/// number.** It is to put back whatever changed in [NaturalKey].
class NaturalKeyTest {

	/// A fixed instant, so the expectations below do not depend on when the
	/// suite runs.
	private static final Date INSTANT = new Date(1787780167411L);

	@Test
	void knownInputsProduceKnownKeys() {
		assertEquals(7183457195969485844L, NaturalKey.idFor());
		assertEquals(2017993895357908097L, NaturalKey.idFor("hello"));
		assertEquals(495325855132766980L, NaturalKey.idFor("f9959179-2e77-48fe-bd45-08cde562e74c"));
	}

	@Test
	void theEntityKeysAreThePinnedOnes() {
		// Each entity's idFor is the tuple its table is keyed on. These three
		// numbers are what is actually stored, so they are pinned as such
		// rather than only through NaturalKey.
		assertEquals(7300517024398243425L,
				Application.idFor("conference", "ashburn", "engine2", INSTANT));
		assertEquals(115498422794456655L,
				Session.idFor("ashburn", 305419896L, INSTANT));
		assertEquals(495325855132766980L,
				Event.idFor("f9959179-2e77-48fe-bd45-08cde562e74c"));
	}

	@Test
	void partsAreFramedSoBoundariesMatter() {
		// Concatenating without framing would make these equal, and two
		// different applications would share one row.
		assertNotEquals(NaturalKey.idFor("ab", "c"), NaturalKey.idFor("a", "bc"));
	}

	@Test
	void nullIsNotTheEmptyString() {
		// A missing server and a server named "" are different facts. Framing
		// null as a negative length is what keeps them apart.
		assertNotEquals(NaturalKey.idFor((Object) null), NaturalKey.idFor(""));
	}

	@Test
	void numbersAgreeWithTheirDecimalText() {
		// Deliberate: a vorpal_id read off the wire as text and the same id
		// held as a long must key the same row.
		assertEquals(NaturalKey.idFor(42L), NaturalKey.idFor("42"));
		assertEquals(NaturalKey.idFor(Integer.valueOf(42)), NaturalKey.idFor("42"));
	}

	@Test
	void datesKeyOnTheirInstantNotTheirText() {
		// The same instant expressed by two Date objects must agree, and
		// millisecond precision must survive — the schema stores milliseconds
		// because a session's birth instant is part of its identity.
		assertEquals(NaturalKey.idFor(INSTANT), NaturalKey.idFor(new Date(INSTANT.getTime())));
		assertNotEquals(NaturalKey.idFor(INSTANT), NaturalKey.idFor(new Date(INSTANT.getTime() + 1)));
	}

	@Test
	void keysAreNeverNegative() {
		// Not correctness, but keys appear in logs and support tickets and a
		// leading minus invites someone to "correct" it.
		assertTrue(NaturalKey.idFor("a") >= 0);
		assertTrue(NaturalKey.idFor("negative-ish", 999L) >= 0);
		for (int i = 0; i < 1000; i++) {
			assertTrue(NaturalKey.idFor("probe", i) >= 0);
		}
	}

	@Test
	void settingTheEventUidSetsTheKey() {
		// The two cannot be set inconsistently: a caller that sets only the
		// uid still gets the right primary key.
		Event event = new Event();
		event.setEventUid("f9959179-2e77-48fe-bd45-08cde562e74c");
		assertEquals(495325855132766980L, event.getId());
	}
}
