package org.vorpal.blade.framework.v3.configuration.templates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/// Registers [TemplatesMXBean] as `vorpal.blade:Type=Templates,Name=<application>`
/// when the application starts and unregisters it when it stops, like
/// `RemotesControl`; declared in the framework JAR's `META-INF/web-fragment.xml`,
/// so every BLADE WAR has it. The domain runtime federates it with a
/// `Location` key per server, which is how the Configurator finds one per server.
///
/// Connectors read a template through `TemplateCache`, which notices the
/// replaced file within a second: a push needs no reload.
public class TemplatesControl implements ServletContextListener, TemplatesMXBean {

	/// Where every connector reads templates (relative to the server's domain home).
	static final Path BASE = Paths.get("config/custom/vorpal/_templates");

	private ObjectName name;

	/// `path` under [#BASE], or an exception when it is not there (`..`, absolute).
	static Path resolve(String path) {
		if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("no template named");
		Path root = BASE.toAbsolutePath().normalize();
		Path p = root.resolve(path).normalize();
		if (!p.startsWith(root) || p.equals(root) || p.toString().contains("/.versions/")) {
			throw new IllegalArgumentException("not a template: " + path);
		}
		return p;
	}

	/// CRC-32 (hex) of `content`'s UTF-8 bytes; the Configurator computes the
	/// same of the admin's file.
	public static String checksum(byte[] content) {
		CRC32 crc = new CRC32();
		crc.update(content);
		return Long.toHexString(crc.getValue());
	}

	@Override
	public String checksum(String path) {
		Path p = resolve(path);
		try {
			return Files.isRegularFile(p) ? checksum(Files.readAllBytes(p)) : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void write(String path, String content) {
		Path p = resolve(path);
		try {
			Files.createDirectories(p.getParent());
			Path temp = Files.createTempFile(p.getParent(), "." + p.getFileName(), ".tmp");
			try {
				Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
				keepMode(temp, p);
				Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/// A temp file is created 0600, and the move would hand that to the
	/// template: it takes the replaced file's mode, or for a new file its
	/// folder's read and write bits. Nothing on a filesystem without modes.
	private static void keepMode(Path temp, Path file) {
		try {
			java.util.Set<java.nio.file.attribute.PosixFilePermission> mode;
			if (Files.exists(file)) {
				mode = Files.getPosixFilePermissions(file);
			} else {
				mode = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
				for (java.nio.file.attribute.PosixFilePermission bit : Files.getPosixFilePermissions(file.getParent())) {
					if (!bit.name().endsWith("EXECUTE")) mode.add(bit);
				}
			}
			Files.setPosixFilePermissions(temp, mode);
		} catch (UnsupportedOperationException | IOException e) {
			// no POSIX modes here
		}
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			String app = sce.getServletContext().getContextPath().replaceFirst("^/", "");
			name = new ObjectName("vorpal.blade:Type=Templates,Name=" + ObjectName.quote(app.isEmpty() ? "root" : app));
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			if (server.isRegistered(name)) server.unregisterMBean(name);
			server.registerMBean(this, name);
		} catch (Exception e) {
			name = null;
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		try {
			if (name != null) ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
		} catch (Exception e) {
			// already gone
		}
		name = null;
	}
}
