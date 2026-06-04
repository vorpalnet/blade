package org.vorpal.blade.framework.v2.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Smoke-test driver for [VersionedFileStore]. Exercises the write → backup →
/// list → restore round trip and the keep-last-N pruning in a throwaway temp
/// directory. Same `main()` convention as the other framework smoke tests;
/// exits non-zero on the first failed expectation.
///
/// ```
/// java -cp target/classes:target/test-classes \
///   org.vorpal.blade.framework.v2.io.VersionedFileStoreSmokeTest
/// ```
public class VersionedFileStoreSmokeTest {

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("vfs-smoke");
		try {
			firstWriteCreatesNoBackup(dir);
			overwriteBacksUpAndRestores(dir);
			pruningKeepsOnlyMax(dir);
		} finally {
			deleteTree(dir);
		}

		if (failures > 0) {
			System.err.println(failures + " expectation(s) failed");
			System.exit(1);
		}
		System.out.println("VersionedFileStoreSmokeTest: all expectations passed");
	}

	private static void firstWriteCreatesNoBackup(Path dir) throws IOException {
		VersionedFileStore store = new VersionedFileStore();
		Path f = dir.resolve("first/new.txt"); // parent doesn't exist yet
		store.write(f, "v1");
		check("first write content", "v1".equals(store.read(f)));
		check("first write makes no version", store.listVersions(f).isEmpty());
	}

	private static void overwriteBacksUpAndRestores(Path dir) throws Exception {
		VersionedFileStore store = new VersionedFileStore();
		Path f = dir.resolve("edit.txt");
		store.write(f, "one");
		Thread.sleep(2); // distinct epoch-millis backup names
		store.write(f, "two");
		Thread.sleep(2);
		store.write(f, "three");

		List<VersionedFileStore.VersionInfo> versions = store.listVersions(f);
		check("two backups after three writes", versions.size() == 2);
		check("current content is latest", "three".equals(store.read(f)));
		// Newest-first ordering.
		check("versions sorted newest first",
				versions.get(0).getTimestamp() >= versions.get(1).getTimestamp());

		// Restore the oldest backup (content "one"); current "three" is backed up too.
		long oldest = versions.get(versions.size() - 1).getTimestamp();
		// Preview must not mutate the live file (still "three" at this point).
		check("readVersion returns backup content", "one".equals(store.readVersion(f, oldest)));
		check("readVersion leaves live file untouched", "three".equals(store.read(f)));
		String restored = store.restore(f, oldest);
		check("restore returns backup content", "one".equals(restored));
		check("restore writes backup content live", "one".equals(store.read(f)));
		check("restore backed up the replaced content", store.listVersions(f).size() == 3);
	}

	private static void pruningKeepsOnlyMax(Path dir) throws Exception {
		VersionedFileStore store = new VersionedFileStore(3); // keep 3
		Path f = dir.resolve("churn.txt");
		store.write(f, "0");
		for (int i = 1; i <= 6; i++) {
			Thread.sleep(2);
			store.write(f, String.valueOf(i));
		}
		// 7 writes → 6 backups eligible, pruned to maxVersions=3.
		check("pruned to maxVersions", store.listVersions(f).size() == 3);
	}

	private static void check(String desc, boolean ok) {
		if (!ok) {
			failures++;
			System.err.println("FAIL [" + desc + "]");
		}
	}

	private static void deleteTree(Path dir) {
		try {
			Files.walk(dir)
					.sorted((a, b) -> b.getNameCount() - a.getNameCount())
					.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (IOException ignored) {
						}
					});
		} catch (IOException ignored) {
		}
	}
}
