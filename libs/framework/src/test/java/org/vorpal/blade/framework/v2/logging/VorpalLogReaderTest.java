package org.vorpal.blade.framework.v2.logging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests for the reader that answers the log viewer over JMX.
///
/// [VorpalLogReader] is one of the few pieces of this feature that runs
/// server-side and is testable outside the container: it takes a plain logs
/// root, so a temporary directory is a complete stand-in for a server's
/// `logs/`. The viewer's own byte arithmetic is browser code and is covered by
/// `admin/logs/src/test/js`.
class VorpalLogReaderTest {

	@TempDir
	Path logsRoot;

	private VorpalLogReader reader;

	@BeforeEach
	void setUp() {
		reader = new VorpalLogReader("engine1", logsRoot);
	}

	private Path write(String name, String content) throws IOException {
		Path p = logsRoot.resolve(name);
		Files.createDirectories(p.getParent());
		Files.write(p, content.getBytes(StandardCharsets.UTF_8));
		return p;
	}

	private static String text(LogSlice s) {
		return new String(s.getBytes(), StandardCharsets.UTF_8);
	}

	// ---- Catalog ----------------------------------------------------------

	@Test
	void catalogClassifiesEachKindAndSkipsLockFiles() throws IOException {
		write("engine1.log", "server\n");
		write("engine1.log00007", "rotated\n");
		write("access.log", "access\n");
		write("stdout.out", "out\n");
		write("engine1.log.lck", "");
		write("notes.txt", "ignored");
		write("vorpal/gateway.0.log", "app\n");
		write("vorpal/gateway.0.log.lck", "");

		List<LogFileInfo> files = Arrays.asList(reader.listLogFiles());
		List<String> paths = files.stream().map(LogFileInfo::getRelativePath).sorted()
				.collect(Collectors.toList());

		assertEquals(Arrays.asList("access.log", "engine1.log", "engine1.log00007",
				"stdout.out", "vorpal/gateway.0.log"), paths);

		for (LogFileInfo f : files) {
			switch (f.getRelativePath()) {
				case "engine1.log":
				case "engine1.log00007":
					assertEquals(LogFileInfo.KIND_WLS_SERVER, f.getKind());
					break;
				case "access.log":
					assertEquals(LogFileInfo.KIND_WLS_ACCESS, f.getKind());
					break;
				case "stdout.out":
					assertEquals(LogFileInfo.KIND_OTHER, f.getKind());
					break;
				case "vorpal/gateway.0.log":
					assertEquals(LogFileInfo.KIND_VORPAL_APP, f.getKind());
					break;
				default:
					break;
			}
		}
	}

	// ---- Slices -----------------------------------------------------------

	@Test
	void negativeOffsetReturnsTheEndOfTheFile() throws IOException {
		write("engine1.log", "alpha\nbravo\ncharlie\n");

		LogSlice s = reader.readSlice("engine1.log", -1, 8);
		assertEquals("charlie\n", text(s));
		assertTrue(s.isEofReached());
		assertEquals(20, s.getNewOffset());
	}

	@Test
	void zeroLengthTailReportsTheFileSize() throws IOException {
		// The viewer's cheap "how big is this file now" probe. It is not a
		// special case in the reader — it falls out of "position so the last 0
		// bytes are returned" — so this pins the behaviour the client relies on.
		write("engine1.log", "alpha\nbravo\n");

		LogSlice s = reader.readSlice("engine1.log", -1, 0);
		assertEquals(0, s.getBytes().length);
		assertEquals(12, s.getNewOffset());
		assertTrue(s.isEofReached());
	}

	@Test
	void sliceFromAnOffsetStopsAtTheRequestedLength() throws IOException {
		write("engine1.log", "alpha\nbravo\ncharlie\n");

		LogSlice s = reader.readSlice("engine1.log", 6, 6);
		assertEquals("bravo\n", text(s));
		assertEquals(12, s.getNewOffset());
		assertFalse(s.isEofReached());
	}

	@Test
	void tailReturnsOnlyWhatWasAppended() throws IOException {
		Path p = write("engine1.log", "alpha\n");
		LogSlice first = reader.tail("engine1.log", 0, 4096);
		assertEquals("alpha\n", text(first));

		Files.write(p, "bravo\n".getBytes(StandardCharsets.UTF_8),
				java.nio.file.StandardOpenOption.APPEND);

		LogSlice second = reader.tail("engine1.log", first.getNewOffset(), 4096);
		assertEquals("bravo\n", text(second));
		assertFalse(second.isTruncatedAtStart());
	}

	@Test
	void tailReportsRotationWhenTheFileShrinksBelowTheCursor() throws IOException {
		write("engine1.log", "the old, longer file\n");
		LogSlice before = reader.tail("engine1.log", 0, 4096);

		write("engine1.log", "new\n");   // rotated: same name, fresh content

		LogSlice after = reader.tail("engine1.log", before.getNewOffset(), 4096);
		assertTrue(after.isTruncatedAtStart(), "rotation must be reported, not silently clamped");
		assertEquals("new\n", text(after));
	}

	// ---- Path safety ------------------------------------------------------

	@Test
	void pathsThatEscapeTheLogsRootAreRejected() {
		for (String bad : new String[] { "../secrets", "a/../../secrets", "/etc/passwd", "\\etc" }) {
			assertThrows(IllegalArgumentException.class,
					() -> reader.readSlice(bad, 0, 16), bad);
			assertThrows(IllegalArgumentException.class,
					() -> reader.search(bad, "x", false, false, 0, 10, 4096), bad);
		}
	}

	// ---- Search -----------------------------------------------------------

	@Test
	void searchReportsTheByteOffsetOfEachMatchingLine() throws IOException {
		write("engine1.log",
				"alpha\n" +      // 0
				"bravo\n" +      // 6
				"alpha two\n" +  // 12
				"charlie\n");    // 22

		LogSearchResult r = reader.search("engine1.log", "alpha", false, false, 0, 100, 1 << 20);

		assertEquals(2, r.getMatches().length);
		assertEquals(0, r.getMatches()[0].getOffset());
		assertEquals("alpha", r.getMatches()[0].getText());
		assertEquals(12, r.getMatches()[1].getOffset());
		assertEquals("alpha two", r.getMatches()[1].getText());
		assertTrue(r.isComplete());
	}

	@Test
	void matchOffsetsAreByteOffsetsNotCharacterOffsets() throws IOException {
		// The viewer jumps the window to these offsets. If they were character
		// counts, every jump after a non-ASCII line would land in the wrong
		// place — and log lines carry SIP display names.
		String first = "café ☎ wörld\n";
		write("engine1.log", first + "target line\n");

		LogSearchResult r = reader.search("engine1.log", "target", false, false, 0, 10, 1 << 20);

		assertEquals(1, r.getMatches().length);
		assertEquals(first.getBytes(StandardCharsets.UTF_8).length, r.getMatches()[0].getOffset());
	}

	@Test
	void aMatchOnAFinalLineWithNoNewlineIsStillFound() throws IOException {
		write("engine1.log", "alpha\nlast line no newline");

		LogSearchResult r = reader.search("engine1.log", "no newline", false, false, 0, 10, 1 << 20);

		assertEquals(1, r.getMatches().length);
		assertEquals(6, r.getMatches()[0].getOffset());
		assertTrue(r.isComplete());
	}

	@Test
	void resumingFromNextOffsetYieldsEveryMatchExactlyOnce() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			sb.append(i % 5 == 0 ? "HIT line " + i + "\n" : "quiet line " + i + "\n");
		}
		write("engine1.log", sb.toString());

		LogSearchResult all = reader.search("engine1.log", "HIT", false, false, 0, 1000, 1 << 20);
		assertEquals(10, all.getMatches().length);

		// Walk the same file two matches at a time and confirm the union is
		// identical — no duplicates across the seam, and no lines skipped.
		List<Long> paged = new ArrayList<>();
		long from = 0;
		boolean complete = false;
		int guard = 0;
		while (!complete && guard++ < 100) {
			LogSearchResult page = reader.search("engine1.log", "HIT", false, false, from, 2, 1 << 20);
			for (LogMatch m : page.getMatches()) {
				paged.add(m.getOffset());
			}
			complete = page.isComplete();
			from = page.getNextOffset();
		}

		Long[] expected = new Long[all.getMatches().length];
		for (int i = 0; i < expected.length; i++) {
			expected[i] = all.getMatches()[i].getOffset();
		}
		assertArrayEquals(expected, paged.toArray(new Long[0]));
	}

	@Test
	void aScanBudgetStopsEarlyAndSaysSo() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			sb.append("HIT padded line number ").append(i).append("\n");
		}
		write("engine1.log", sb.toString());

		LogSearchResult r = reader.search("engine1.log", "HIT", false, false, 0, 100000, 4096);

		assertFalse(r.isComplete(), "a budgeted scan must not claim it read the whole file");
		assertTrue(r.getBytesScanned() <= 4096,
				"scanned " + r.getBytesScanned() + " with a 4096 budget");
		assertTrue(r.getNextOffset() > 0 && r.getNextOffset() <= 4096);
	}

	@Test
	void theMatchCeilingIsEnforcedEvenWhenTheCallerAsksForMore() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < VorpalLogReader.MAX_MATCHES_PER_CALL + 500; i++) {
			sb.append("HIT ").append(i).append("\n");
		}
		write("engine1.log", sb.toString());

		LogSearchResult r = reader.search("engine1.log", "HIT", false, false, 0,
				Integer.MAX_VALUE, Long.MAX_VALUE);

		assertEquals(VorpalLogReader.MAX_MATCHES_PER_CALL, r.getMatches().length);
		assertFalse(r.isComplete());
	}

	@Test
	void caseInsensitiveLiteralSearchMatchesEitherCase() throws IOException {
		write("engine1.log", "Alpha\nBRAVO\ncharlie\n");

		LogSearchResult sensitive = reader.search("engine1.log", "alpha", false, false, 0, 10, 1 << 20);
		assertEquals(0, sensitive.getMatches().length);

		LogSearchResult insensitive = reader.search("engine1.log", "alpha", false, true, 0, 10, 1 << 20);
		assertEquals(1, insensitive.getMatches().length);
	}

	@Test
	void regexModeMatchesPatternsAndLiteralModeDoesNot() throws IOException {
		write("engine1.log", "INVITE sip:alice@example.test\nBYE sip:bob@example.test\n");

		LogSearchResult asRegex = reader.search("engine1.log", "^(INVITE|BYE) sip:", true, false, 0, 10, 1 << 20);
		assertEquals(2, asRegex.getMatches().length);

		LogSearchResult asLiteral = reader.search("engine1.log", "^(INVITE|BYE) sip:", false, false, 0, 10, 1 << 20);
		assertEquals(0, asLiteral.getMatches().length,
				"the default must treat the pattern as text, not as a regex");
	}

	@Test
	void aBrokenRegexReportsTheOperatorsMistakeNotAnEngineStackTrace() throws IOException {
		write("engine1.log", "alpha\n");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> reader.search("engine1.log", "unclosed (group", true, false, 0, 10, 1 << 20));
		assertTrue(e.getMessage().startsWith("invalid regular expression:"), e.getMessage());
	}

	@Test
	void anEmptyPatternIsRejected() throws IOException {
		write("engine1.log", "alpha\n");

		assertThrows(IllegalArgumentException.class,
				() -> reader.search("engine1.log", "", false, false, 0, 10, 1 << 20));
		assertThrows(IllegalArgumentException.class,
				() -> reader.search("engine1.log", null, false, false, 0, 10, 1 << 20));
	}

	@Test
	void searchingAMissingFileReturnsNothingRatherThanThrowing() {
		// Consistent with readSlice and tail: an unreadable file is an empty
		// answer, so one rotated-away file cannot break the console.
		LogSearchResult r = reader.search("engine1.log", "alpha", false, false, 0, 10, 1 << 20);

		assertEquals(0, r.getMatches().length);
		assertTrue(r.isComplete());
	}
}
