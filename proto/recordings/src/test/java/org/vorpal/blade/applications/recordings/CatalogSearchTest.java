package org.vorpal.blade.applications.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.media.manifest.Redactor;

/// The shape of a catalog query, and what the audit log is told about it.
class CatalogSearchTest {

	@Test
	@DisplayName("every filter is optional and they combine with AND")
	void buildsTheFilters() {
		CatalogSearch.Query q = new CatalogSearch.Query();
		q.from = LocalDate.of(2026, 9, 8);
		q.to = LocalDate.of(2026, 9, 9);
		q.number = "(816) 555";
		q.attributes.put("department", "cardiology");
		q.kinds.add("card");
		q.words = "cancel appointment";
		q.limit = 20;
		CatalogSearch.Prepared p = CatalogSearch.prepare(q, true);
		assertTrue(p.sql.contains("c.EPOCH_UTC >= ?"));
		assertTrue(p.sql.contains("c.EPOCH_UTC < ?"));
		assertTrue(p.sql.contains("c.FROM_NUMBER LIKE ? OR c.TO_NUMBER LIKE ?"));
		assertTrue(p.sql.contains("a.NAME = ? AND a.VALUE = ?"));
		assertTrue(p.sql.contains("k.KINDS = ?"));
		assertTrue(p.sql.contains("CONTAINS(u.TEXT, ?, 1) > 0"));
		assertTrue(p.sql.endsWith("ORDER BY c.EPOCH_UTC DESC FETCH FIRST ? ROWS ONLY"));
		assertEquals(Timestamp.valueOf("2026-09-08 00:00:00"), toLocal(p.params.get(0)));
		assertEquals("816555%", p.params.get(2), "a number is matched by its digits, as a prefix");
		assertEquals("cardiology", p.params.get(5));
		assertEquals("{cancel} AND {appointment}", p.params.get(10), "full text: every word required");
		assertEquals(20, p.params.get(p.params.size() - 1));
	}

	private static Timestamp toLocal(Object utc) {
		// The parameter is the UTC instant; compare it as the UTC wall time.
		return Timestamp.valueOf(((Timestamp) utc).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
	}

	@Test
	@DisplayName("without a full-text index the words become a scan, one per word")
	void scansWithoutTheIndex() {
		CatalogSearch.Query q = new CatalogSearch.Query();
		q.words = "rent a car";
		CatalogSearch.Prepared p = CatalogSearch.prepare(q, false);
		assertEquals(3, p.sql.split("UPPER\\(u.TEXT\\) LIKE \\?").length - 1);
		assertEquals(Arrays.asList("%RENT%", "%A%", "%CAR%", 100), p.params);
	}

	@Test
	@DisplayName("a protected value is searched by its hash and never appears in the SQL")
	void searchesProtectedValuesByDigest() {
		CatalogSearch.Query q = new CatalogSearch.Query();
		q.protectedDigest = Redactor.digest("k", "KR742916");
		CatalogSearch.Prepared p = CatalogSearch.prepare(q, true);
		assertTrue(p.sql.contains("p.DIGEST = ?"));
		assertFalse(p.sql.contains("KR742916"));
		assertEquals(q.protectedDigest, p.params.get(0));
	}

	@Test
	@DisplayName("the audit log sees the filters and not the numbers")
	void describesWithoutContent() {
		CatalogSearch.Query q = new CatalogSearch.Query();
		q.from = LocalDate.of(2026, 9, 8);
		q.number = "8165550142";
		q.attributes.put("department", "cardiology");
		q.words = "call me at 816-555-0142 about my card 4111 1111 1111 1111";
		String d = RecordingsAPI.describe(q, Redactor.defaults());
		assertEquals("days=2026-09-08..null number=[phone] department=cardiology "
				+ "words=\"call me at [phone] about my card [card]\"", d);
		assertFalse(d.contains("0142"));
		assertFalse(d.contains("4111"));
	}

	@Test
	void recordingIdIsTheUpperCasedConversation() {
		assertEquals("0C360643.1A08456988D", CatalogSearch.recordingIdOf("0C360643.1a08456988d"));
		assertEquals("0C360643.1a08456988d", TranscriptView.conversationOf(CatalogSearch.recordingIdOf("0C360643.1a08456988d")));
	}
}
