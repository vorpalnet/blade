package org.vorpal.blade.framework.v3.deploy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// What an archive holds, as one hash, so two builds of the same code compare
/// equal.
///
/// A WAR or EAR is never byte-identical twice: every entry carries the time it
/// was written, the manifest carries the build date, and the jars inside it
/// carry their own. This hashes each entry's name and content instead, in name
/// order, skips the manifest and Maven's metadata, and hashes an archive inside
/// an archive by its own content the same way. `upgrade.sh` compares the build
/// against what the domain deployed and leaves an unchanged application alone,
/// since every redeploy loads its classes again and the engine's metaspace
/// grows until a restart.
public final class ArchiveDigest {

	private ArchiveDigest() {
	}

	/// The content hash of this archive, in hex.
	public static String of(InputStream archive) throws IOException {
		return hex(digest(archive));
	}

	private static byte[] digest(InputStream archive) throws IOException {
		Map<String, byte[]> entries = new TreeMap<>();
		ZipInputStream zip = new ZipInputStream(archive);
		for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
			String name = e.getName();
			if (e.isDirectory() || ignored(name)) {
				continue;
			}
			byte[] content = readAll(zip);
			entries.put(name, nested(name) ? digest(new ByteArrayInputStream(content)) : sha256().digest(content));
		}
		MessageDigest all = sha256();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			all.update(e.getKey().getBytes(StandardCharsets.UTF_8));
			all.update((byte) 0);
			all.update(e.getValue());
		}
		return all.digest();
	}

	/// Entries that change with every build and say nothing about the code.
	static boolean ignored(String name) {
		return name.equals("META-INF/MANIFEST.MF") || (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties"));
	}

	private static boolean nested(String name) {
		String n = name.toLowerCase(java.util.Locale.ROOT);
		return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".ear") || n.endsWith(".rar");
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		for (int n; (n = in.read(buf)) > 0;) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String hex(byte[] b) {
		StringBuilder s = new StringBuilder();
		for (byte x : b) {
			s.append(String.format("%02x", x));
		}
		return s.toString();
	}

	/// `ArchiveDigest <file>...`: one `<hash>  <file>` line per archive.
	public static void main(String[] args) throws IOException {
		for (String f : args) {
			try (InputStream in = Files.newInputStream(Paths.get(f))) {
				System.out.println(of(in) + "  " + f);
			}
		}
	}
}
