package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.http.HttpServletRequest;

/// The version of a configuration file an editor loaded, so a save can
/// refuse to overwrite a file that changed after the page loaded it.
///
/// An editor page holds the whole document from load until save. Without a
/// check, a save writes back what the page loaded and silently undoes any
/// edit made in between: the Configurator's logging change, another
/// operator's rule. The editor puts the version from load in an `If-Match`
/// header on save; a save without the header is not checked.
public final class FileVersion {

	/// The monitor every writer of `file` holds while it checks its version
	/// and writes: one per file for the whole JVM. Each editor WAR carries its
	/// own copy of this class, so a class-level lock would not keep two
	/// editors apart; an interned string is shared by every class loader.
	public static Object lock(java.nio.file.Path file) {
		return ("org.vorpal.blade.config:" + file.toAbsolutePath().normalize()).intern();
	}


	/// Version of a file that does not exist yet.
	public static final String NONE = "none";

	private FileVersion() {
	}

	/// A hash of the file's bytes, or [#NONE].
	public static String of(Path file) throws IOException {
		if (!Files.exists(file)) return NONE;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 12; i++) hex.append(String.format("%02x", digest[i]));
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/// True unless the request names a version (`If-Match`) and the file is
	/// no longer at it.
	public static boolean current(HttpServletRequest request, Path file) throws IOException {
		String expected = request.getHeader("If-Match");
		if (expected == null || expected.isEmpty() || "*".equals(expected)) return true;
		return expected.replace("\"", "").trim().equals(of(file));
	}

	/// The 409 message for a save refused by [#current].
	public static String conflict(Path file) {
		return file.getFileName() + " changed after this page loaded it, so saving would undo that change. "
				+ "Reload to pick it up, then make your edit again.";
	}
}
