package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// What the redactor finds in the text a recognizer writes, and what it
/// leaves alone.
class RedactorTest {

	private static List<Utterance.Redaction> find(String text) {
		return Redactor.defaults().find(text);
	}

	private static String masked(String text) {
		return Redactor.mask(text, find(text));
	}

	@Nested
	@DisplayName("the built-in kinds")
	class Kinds {

		@Test
		void aCardNumberByItsCheckDigit() {
			assertEquals("My card is [card].", masked("My card is 4111 1111 1111 1111."));
			assertEquals("card", find("4111111111111111").get(0).getKind());
			// Same shape, wrong check digit: an account number, not a card.
			assertEquals("number", find("4111 1111 1111 1112").get(0).getKind());
		}

		@Test
		void aSocialSecurityNumber() {
			assertEquals("It's [ssn], yes.", masked("It's 078-05-1120, yes."));
			assertEquals("ssn", find("078 05 1120").get(0).getKind());
		}

		@Test
		void aPhoneNumber() {
			assertEquals("Call me at [phone].", masked("Call me at (816) 555-0142."));
			assertEquals("phone", find("816-555-0142").get(0).getKind());
		}

		@Test
		void aMemberIdentifier() {
			assertEquals("Member [number], date of birth [date].", masked("Member KR742916, date of birth 3/5/1972."));
			assertEquals("[number]", masked("742916"));
		}

		@Test
		void aSpokenDateAndAnAddress() {
			assertEquals("Born [date], living at [address]", masked("Born March 3rd, 1974, living at 1600 Pennsylvania Avenue"));
			assertEquals("It's [address].", masked("It's 42 Elm Street."));
		}

		@Test
		void leavesOrdinarySpeechAlone() {
			assertTrue(find("I need the car for Tuesday, please.").isEmpty());
			assertTrue(find("It's about 2 hours, maybe 3.").isEmpty(), "short numbers are not identifiers");
			assertTrue(find("Room 4120 on the 2nd floor").isEmpty());
		}

		@Test
		void aCharacterBelongsToOneSpan() {
			// The card claims its sixteen digits first; the ssn rule must not
			// carve a second span out of the same digits.
			List<Utterance.Redaction> spans = find("4111 1111 1111 1111");
			assertEquals(1, spans.size());
		}
	}

	@Nested
	@DisplayName("configuration")
	class Config {

		@Test
		void addsAKindAndKeepsTheRest() {
			Map<String, String> kinds = new LinkedHashMap<>();
			kinds.put("claim", "\\bCLM-\\d{6}\\b");
			Redactor r = Redactor.of(kinds);
			List<Utterance.Redaction> spans = r.find("Claim CLM-004512 on card 4111 1111 1111 1111");
			assertEquals("claim", spans.get(0).getKind());
			assertEquals("card", spans.get(1).getKind());
		}

		@Test
		void anEmptyExpressionRemovesAKind() {
			Redactor r = Redactor.of(Collections.singletonMap("number", ""));
			assertTrue(r.find("Member KR742916").isEmpty());
			assertFalse(r.isEmpty());
		}

		@Test
		void theCallsOwnValuesUnderTheirOwnKinds() {
			Map<String, String> known = new LinkedHashMap<>();
			known.put("callerName", "Yolanda Okonkwo");
			known.put("memberId", "KR742916");
			Redactor r = Redactor.defaults().withPhrases(known);
			assertEquals("Hello, this is [callerName] and my id is [memberId].",
					Redactor.mask("Hello, this is Yolanda Okonkwo and my id is KR742916.",
							r.find("Hello, this is Yolanda Okonkwo and my id is KR742916.")));
			assertEquals("callerName", r.find("yolanda, okonkwo here").get(0).getKind(), "case and punctuation tolerant");
			assertTrue(r.find("Yolandaville").isEmpty(), "not inside another word");
			assertFalse(Redactor.none().withPhrases(known).isEmpty(), "known values are found even with patterns off");
		}

		@Test
		void noneFindsNothing() {
			assertTrue(Redactor.none().isEmpty());
			Utterance u = new Utterance("caller", 0, 1000, "4111 1111 1111 1111");
			assertFalse(Redactor.none().apply(u));
			assertNull(u.getRedacted());
		}
	}

	@Nested
	@DisplayName("applied to an utterance")
	class Applied {

		@Test
		void keepsTheTextAndAddsTheRendition() {
			Utterance u = new Utterance("caller", 1000, 4000, "My member id is KR742916.");
			assertTrue(Redactor.defaults().apply(u));
			assertEquals("My member id is KR742916.", u.getText(), "the record keeps what was said");
			assertEquals("My member id is [number].", u.getRedacted());
			assertEquals(1, u.getRedactions().size());
			assertEquals(16, u.getRedactions().get(0).getFrom());
			assertEquals(24, u.getRedactions().get(0).getTo());
			assertNull(u.getRedactions().get(0).getStartMillis(), "no word timing, no span timing");
		}

		@Test
		void aCleanUtteranceStillGetsARendition() {
			Utterance u = new Utterance("caller", 0, 1000, "I need to rent a car.");
			assertFalse(Redactor.defaults().apply(u));
			assertEquals("I need to rent a car.", u.getRedacted(), "a reader of the redacted rendition sees every clean line");
			assertTrue(u.getRedactions().isEmpty());
		}

		@Test
		void placesTheSpanOnTheConversationClock() throws Exception {
			Utterance u = new Utterance("caller", 10_000, 14_000, "Card 4111 1111 1111 1111 please.");
			u.setWords(new ObjectMapper().readTree("{\"tokens\":[\" Card\",\" 4111\",\" 1111\",\" 1111\",\" 1111\",\" please\",\".\"],"
					+ "\"timestamps\":[0.0,0.5,1.0,1.5,2.0,2.6,2.9],\"durations\":[0.4,0.4,0.4,0.4,0.4,0.3,0.1]}"));
			assertTrue(Redactor.defaults().apply(u));
			Utterance.Redaction span = u.getRedactions().get(0);
			assertEquals("card", span.getKind());
			assertEquals(10_500L, span.getStartMillis(), "the first digit group");
			assertEquals(12_400L, span.getEndMillis(), "the end of the last digit group");
		}
	}
}
