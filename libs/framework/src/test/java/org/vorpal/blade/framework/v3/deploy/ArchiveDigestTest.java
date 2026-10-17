package org.vorpal.blade.framework.v3.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchiveDigestTest {

	/// An archive of name/content pairs, every entry stamped with this time.
	private static byte[] zip(long time, String... nameContent) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(out)) {
			for (int i = 0; i < nameContent.length; i += 2) {
				ZipEntry e = new ZipEntry(nameContent[i]);
				e.setTime(time);
				z.putNextEntry(e);
				z.write(nameContent[i + 1].getBytes(StandardCharsets.ISO_8859_1));
				z.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static String digest(byte[] archive) throws IOException {
		return ArchiveDigest.of(new ByteArrayInputStream(archive));
	}

	private static String war(long time, String clazz, String manifestDate) throws IOException {
		String lib = new String(zip(time, "A.class", "framework"), StandardCharsets.ISO_8859_1);
		return new String(zip(time, "META-INF/MANIFEST.MF", "Build-Date: " + manifestDate, "WEB-INF/classes/X.class", clazz,
				"WEB-INF/lib/blade-framework.jar", lib), StandardCharsets.ISO_8859_1);
	}

	@Test
	@DisplayName("two builds of the same code compare equal, though every time and date differs")
	void sameCodeSameDigest() throws IOException {
		byte[] monday = war(1_000_000L, "code", "monday").getBytes(StandardCharsets.ISO_8859_1);
		byte[] tuesday = war(9_000_000L, "code", "tuesday").getBytes(StandardCharsets.ISO_8859_1);
		assertNotEquals(new String(monday, StandardCharsets.ISO_8859_1), new String(tuesday, StandardCharsets.ISO_8859_1));
		assertEquals(digest(monday), digest(tuesday));
	}

	@Test
	@DisplayName("a changed class, even inside a nested jar, changes the digest")
	void changedCodeDiffers() throws IOException {
		assertNotEquals(digest(war(1L, "code", "d").getBytes(StandardCharsets.ISO_8859_1)),
				digest(war(1L, "other", "d").getBytes(StandardCharsets.ISO_8859_1)));
		byte[] a = zip(1L, "lib/x.jar", new String(zip(1L, "A.class", "one"), StandardCharsets.ISO_8859_1));
		byte[] b = zip(1L, "lib/x.jar", new String(zip(1L, "A.class", "two"), StandardCharsets.ISO_8859_1));
		assertNotEquals(digest(a), digest(b));
	}
}
