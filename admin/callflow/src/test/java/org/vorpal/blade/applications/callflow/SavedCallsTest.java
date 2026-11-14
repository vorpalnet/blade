package org.vorpal.blade.applications.callflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// A call saved from the page comes back as the page sent it, holding only that
/// call's steps, and its folder name can only ever be a Vorpal-ID.
class SavedCallsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode page() throws Exception {
		return MAPPER.readTree("{\"sources\":["
				+ "{\"app\":\"tpcc\",\"server\":\"engine0\",\"steps\":["
				+ "{\"sessionId\":\"8F641CF7\",\"epochMillis\":2000,\"label\":\"INVITE\"},"
				+ "{\"sessionId\":\"CD9C9D0D\",\"epochMillis\":1000,\"label\":\"REGISTER\"}]},"
				+ "{\"app\":\"gateway\",\"server\":\"engine0\",\"steps\":["
				+ "{\"sessionId\":\"8f641cf7\",\"epochMillis\":1990,\"label\":\"INVITE\"}]},"
				+ "{\"app\":\"meetings\",\"server\":\"engine0\",\"steps\":["
				+ "{\"sessionId\":\"CD9C9D0D\",\"epochMillis\":1001,\"label\":\"200\"}]}],"
				+ "\"sourceFiles\":{\"tpcc|org.example.DialogAPI\":\"class DialogAPI {}\"}}");
	}

	@Test
	void aSavedCallComesBackWithOnlyItsOwnSteps(@TempDir Path root) throws Exception {
		JsonNode summary = SavedCalls.write(root, "8F641CF7", page(), "jeff");
		assertEquals(2, summary.path("steps").asInt());
		assertEquals(1990, summary.path("firstMillis").asLong());
		assertEquals("[\"tpcc\",\"gateway\"]", summary.path("apps").toString());

		JsonNode trace = MAPPER.readTree(SavedCalls.read(root, "8F641CF7"));
		assertEquals("jeff", trace.path("savedBy").asText());
		assertEquals(2, trace.path("sources").size(), "meetings has no step of this call");
		assertEquals(1, trace.path("sources").get(0).path("steps").size());
		assertEquals("class DialogAPI {}", trace.path("sourceFiles").path("tpcc|org.example.DialogAPI").asText());

		JsonNode list = SavedCalls.list(root);
		assertEquals(1, list.path("calls").size());
		assertEquals("8F641CF7", list.path("calls").get(0).path("id").asText());
	}

	@Test
	void savingAgainReplacesAndDeleteRemoves(@TempDir Path root) throws Exception {
		SavedCalls.write(root, "8F641CF7", page(), "jeff");
		SavedCalls.write(root, "8F641CF7", page(), "pat");
		assertEquals(1, SavedCalls.list(root).path("calls").size());
		assertEquals("pat", MAPPER.readTree(SavedCalls.read(root, "8F641CF7")).path("savedBy").asText());
		assertTrue(SavedCalls.delete(root, "8F641CF7"));
		assertFalse(SavedCalls.delete(root, "8F641CF7"));
		assertNull(SavedCalls.read(root, "8F641CF7"));
		assertEquals(0, SavedCalls.list(root).path("calls").size());
	}

	@Test
	void theFoldersAreTheOwnersOnly(@TempDir Path root) throws Exception {
		Path dir = root.resolve("traces");
		SavedCalls.write(dir, "8F641CF7", page(), "jeff");
		assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
		assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("8F641CF7"))));
	}

	@Test
	void onlyAVorpalIdNamesAFolder() {
		assertEquals("8F641CF7", SavedCalls.vorpalId(" 8f641cf7 "));
		assertNull(SavedCalls.vorpalId("../etc"));
		assertNull(SavedCalls.vorpalId("8F641CF7/.."));
		assertNull(SavedCalls.vorpalId(""));
		assertNull(SavedCalls.vorpalId(null));
	}

	@Test
	void aCallWithNoStepsInTheRequestIsRefused(@TempDir Path root) {
		assertThrows(IllegalArgumentException.class, () -> SavedCalls.write(root, "0000ABCD", page(), "jeff"));
		assertThrows(IllegalArgumentException.class,
				() -> SavedCalls.write(root, "8F641CF7", MAPPER.readTree("{}"), "jeff"));
	}

	@Test
	void aLeadingTildeIsHome() {
		assertEquals(Path.of(System.getProperty("user.home"), ".blade", "traces"), SavedCalls.directory(null));
		assertEquals(Path.of("/srv/traces"), SavedCalls.directory("/srv/traces"));
	}
}
