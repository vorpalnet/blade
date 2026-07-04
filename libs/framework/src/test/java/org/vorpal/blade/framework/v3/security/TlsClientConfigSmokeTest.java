package org.vorpal.blade.framework.v3.security;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;

import javax.net.ssl.SSLContext;

/// Offline smoke test for [TlsClientConfig] — verifies the config→SSLContext
/// plumbing without a network peer. A real truststore is synthesized by
/// copying a CA certificate out of the running JDK's `cacerts` into a fresh
/// PKCS12 file, so no fixture files ship in the repo. The mutual-TLS
/// handshake itself can only be proven against a live endpoint (see
/// SECURITY.md); what this covers is: empty config → null (JVM default),
/// configured truststore → working SSLContext, missing/garbage store →
/// exception (fail closed, never silent default trust).
///
/// Same `main()` + pass/fail-counter style as `JwtValidatorSmokeTest`
/// (the framework module has no JUnit).
///
/// Run: compile the test sources, then
/// `java -cp <framework classes + test classes> \
///   org.vorpal.blade.framework.v3.security.TlsClientConfigSmokeTest`
public final class TlsClientConfigSmokeTest {

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		testEmptyConfigBuildsNothing();
		testTruststoreBuildsContext();
		testMissingStoreFailsClosed();
		testGarbageStoreFailsClosed();

		System.out.println("TlsClientConfigSmokeTest: " + passed + " passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}

	private static void testEmptyConfigBuildsNothing() throws Exception {
		TlsClientConfig cfg = new TlsClientConfig();
		check("empty config isEmpty()", cfg.isEmpty());
		check("empty config builds null (JVM default)", cfg.buildSslContext() == null);

		cfg.setTrustStorePassword("irrelevant");
		cfg.setTrustStoreType("JKS");
		check("passwords/types alone still isEmpty()", cfg.isEmpty());
	}

	private static void testTruststoreBuildsContext() throws Exception {
		Path store = synthesizeTruststore("blade-tls-smoke");
		try {
			TlsClientConfig cfg = new TlsClientConfig();
			cfg.setTrustStore(store.toString());
			cfg.setTrustStorePassword("changeit");
			check("configured truststore not isEmpty()", !cfg.isEmpty());
			SSLContext ctx = cfg.buildSslContext();
			check("truststore builds an SSLContext", ctx != null);
			check("context yields a socket factory", ctx != null && ctx.getSocketFactory() != null);
		} finally {
			Files.deleteIfExists(store);
		}
	}

	private static void testMissingStoreFailsClosed() {
		TlsClientConfig cfg = new TlsClientConfig();
		cfg.setTrustStore("/nonexistent/blade-smoke-missing.p12");
		cfg.setTrustStorePassword("whatever");
		try {
			cfg.buildSslContext();
			check("missing truststore throws", false);
		} catch (Exception expected) {
			check("missing truststore throws", true);
		}
	}

	private static void testGarbageStoreFailsClosed() throws Exception {
		Path garbage = Files.createTempFile("blade-tls-smoke-garbage", ".p12");
		Files.write(garbage, "this is not a keystore".getBytes());
		try {
			TlsClientConfig cfg = new TlsClientConfig();
			cfg.setKeyStore(garbage.toString());
			cfg.setKeyStorePassword("whatever");
			cfg.buildSslContext();
			check("garbage keystore throws", false);
		} catch (Exception expected) {
			check("garbage keystore throws", true);
		} finally {
			Files.deleteIfExists(garbage);
		}
	}

	/// Build a one-cert PKCS12 truststore (password `changeit`) by lifting the
	/// first trusted CA out of the running JDK's default trust anchors.
	private static Path synthesizeTruststore(String prefix) throws Exception {
		KeyStore cacerts = KeyStore.getInstance(KeyStore.getDefaultType());
		Path cacertsPath = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
		try (var in = Files.newInputStream(cacertsPath)) {
			cacerts.load(in, "changeit".toCharArray());
		}
		Certificate anchor = null;
		for (Enumeration<String> e = cacerts.aliases(); e.hasMoreElements();) {
			String alias = e.nextElement();
			if (cacerts.isCertificateEntry(alias)) {
				anchor = cacerts.getCertificate(alias);
				break;
			}
		}
		if (anchor == null) {
			throw new IllegalStateException("no trusted certificate found in JDK cacerts");
		}

		KeyStore p12 = KeyStore.getInstance("PKCS12");
		p12.load(null, null);
		p12.setCertificateEntry("anchor", anchor);
		Path file = Files.createTempFile(prefix, ".p12");
		try (OutputStream out = Files.newOutputStream(file)) {
			p12.store(out, "changeit".toCharArray());
		}
		return file;
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}
}
