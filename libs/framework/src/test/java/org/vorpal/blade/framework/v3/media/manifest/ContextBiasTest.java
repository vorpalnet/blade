package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/// The misspellings are what the recognizers actually wrote on the rig for
/// these names through the G.711 round trip, 2026-09-08, with and without
/// hotwords. The corrections have to catch those and refuse ordinary words.
public class ContextBiasTest {

	private static final ContextBias NAMES = ContextBias.of(List.of("Yolanda Okonkwo", "Priyanka Venkataraman", "Vorpal"));

	@Test
	public void theSameSoundsSpeltDifferentlyAreCorrected() {
		assertEquals("Hello, this is Yolanda Okonkwo calling about my account.",
				NAMES.correct("Hello, this is Yolanda Akongwo calling about my account.", null));
		assertEquals("Hello, this is Yolanda Okonkwo calling about my account.",
				NAMES.correct("Hello, this is Yolanda Aconquo calling about my account.", null));
		assertEquals("I spoke with Priyanka Venkataraman last week.",
				NAMES.correct("I spoke with Priyanka Venkateraman last week.", null));
		assertEquals("Vorpal, cancel my appointment.", NAMES.correct("Porpal, cancel my appointment.", null));
		assertEquals("Vorpal, cancel my appointment.", NAMES.correct("Forbel, cancel my appointment.", null));
		assertEquals("Vorpal cancel my appointment.", NAMES.correct("Borp will cancel my appointment.", null));
		// an inserted syllable, from a real call: letters decide a long name
		assertEquals("Hello, this is Yolanda Okonkwo calling about my account.",
				NAMES.correct("Hello, this is Yolanda of Conquer calling about my account.", null));
	}

	@Test
	public void theClosestWindowWins() {
		ContextBias ids = ContextBias.of(List.of("ABC12345"));
		assertEquals("The policy number is ABC12345.", ids.correct("The policy number is a BC 12345.", null),
				"the exact three-token window, not the two-token near miss inside it");
	}

	@Test
	public void ordinaryWordsThatMerelySoundAlikeAreLeftAlone() {
		assertEquals("Send it for Bill, please.", NAMES.correct("Send it for Bill, please.", null));
		assertEquals("I need four poles.", NAMES.correct("I need four poles.", null));
		assertEquals("What is the weather like in Chicago?", NAMES.correct("What is the weather like in Chicago?", null));
	}

	@Test
	public void aPhraseAlreadyRightIsNotTouched() {
		List<Utterance.Correction> made = new ArrayList<>();
		String text = "This is Yolanda Okonkwo.";
		assertEquals(text, NAMES.correct(text, made));
		assertTrue(made.isEmpty());
	}

	@Test
	public void identifiersMatchOnLettersAndDigitsWithinOneCharacter() {
		ContextBias ids = ContextBias.of(List.of("KR742916", "ABC12345"));
		assertEquals("My member ID is KR742916.", ids.correct("My member ID is KR 742916.", null));
		assertEquals("My member ID is KR742916.", ids.correct("My member ID is K.R. 742916.", null));
		assertEquals("My member ID is KR742916.", ids.correct("My member ID is KR74291.", null), "one dropped digit");
		assertEquals("The policy number is ABC12345.", ids.correct("The policy number is EBC 12345.", null));
		assertEquals("The total is 742.", ids.correct("The total is 742.", null), "a short run of digits is not an id");
	}

	@Test
	public void theUtteranceKeepsWhatWasHeard() {
		Utterance u = new Utterance("caller", 0L, 1500L, "Porpal, cancel my appointment.");
		assertTrue(NAMES.apply(u));
		assertEquals("Vorpal, cancel my appointment.", u.getText());
		assertEquals("Porpal, cancel my appointment.", u.getHeard());
		assertEquals(1, u.getCorrections().size());
		assertEquals("Porpal,", u.getCorrections().get(0).getFrom());
		assertEquals("Vorpal", u.getCorrections().get(0).getTo());

		Utterance clean = new Utterance("caller", 0L, 1500L, "I want to rent a car.");
		assertFalse(NAMES.apply(clean));
		assertNull(clean.getHeard());
		assertNull(clean.getCorrections());
	}

	@Test
	public void nothingExpectedMeansNothingChanges() {
		assertTrue(ContextBias.of(null).isEmpty());
		assertEquals("Porpal here.", ContextBias.of(List.of("", "  ")).correct("Porpal here.", null));
	}

	@Test
	public void thePhoneticKeyMeetsWhereTheEarDoes() {
		assertEquals(ContextBias.phonetic("vorpal"), ContextBias.phonetic("forbel"));
		assertEquals(ContextBias.phonetic("vorpal"), ContextBias.phonetic("fourpole"));
		assertEquals(ContextBias.phonetic("okonkwo"), ContextBias.phonetic("aconquo"));
		assertFalse(ContextBias.phonetic("vorpal").equals(ContextBias.phonetic("chicago")));
	}
}
