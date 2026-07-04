package org.vorpal.blade.framework.v3.security;

import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// TLS settings for an **outbound** HTTPS connection made by BLADE — a
/// [org.vorpal.blade.framework.v3.configuration.connectors.RestConnector]
/// call, or any other client the framework originates.
///
/// Both halves are optional and independent:
///
/// - **Trust** (`trustStore`) — a keystore of CA certificates BLADE should
///   trust when verifying the server. Leave blank to use the JVM's default
///   truststore, which is the normal case: load the customer's CA into the
///   server JVM (see `certs.sh` and `SECURITY.md`) and every outbound
///   client — including the OAuth token fetches and the JWKS fetch — trusts
///   it with no per-connector config. Set this only when one endpoint needs
///   a trust anchor you do not want JVM-wide.
/// - **Client identity** (`keyStore`) — a keystore holding this server's
///   client certificate + private key, presented when the remote endpoint
///   requires **mutual TLS**. Leave blank for ordinary server-auth TLS.
///
/// Paths are absolute or relative to the WLS server working directory
/// (same convention as `./config/custom/vorpal/`). Store type defaults to
/// PKCS12; JKS is accepted for legacy stores.
///
/// An unset config (both stores blank) builds no [SSLContext] — callers
/// must fall back to the JVM default, so a blank config changes nothing.
public class TlsClientConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	private String trustStore;
	private String trustStorePassword;
	private String trustStoreType = "PKCS12";
	private String keyStore;
	private String keyStorePassword;
	private String keyStoreType = "PKCS12";

	@JsonPropertyDescription("Path to a keystore of trusted CA certificates for verifying the remote server. Leave blank to use the JVM default truststore (the normal case — load the customer CA JVM-wide instead).")
	public String getTrustStore() {
		return trustStore;
	}

	public void setTrustStore(String trustStore) {
		this.trustStore = trustStore;
	}

	@JsonPropertyDescription("Password for the truststore.")
	public String getTrustStorePassword() {
		return trustStorePassword;
	}

	public void setTrustStorePassword(String trustStorePassword) {
		this.trustStorePassword = trustStorePassword;
	}

	@JsonPropertyDescription("Truststore type: PKCS12 (default) or JKS.")
	public String getTrustStoreType() {
		return trustStoreType;
	}

	public void setTrustStoreType(String trustStoreType) {
		this.trustStoreType = trustStoreType;
	}

	@JsonPropertyDescription("Path to a keystore holding this server's client certificate and private key, presented when the endpoint requires mutual TLS. Leave blank for ordinary server-auth TLS.")
	public String getKeyStore() {
		return keyStore;
	}

	public void setKeyStore(String keyStore) {
		this.keyStore = keyStore;
	}

	@JsonPropertyDescription("Password for the client keystore (also used for its private-key entries).")
	public String getKeyStorePassword() {
		return keyStorePassword;
	}

	public void setKeyStorePassword(String keyStorePassword) {
		this.keyStorePassword = keyStorePassword;
	}

	@JsonPropertyDescription("Client keystore type: PKCS12 (default) or JKS.")
	public String getKeyStoreType() {
		return keyStoreType;
	}

	public void setKeyStoreType(String keyStoreType) {
		this.keyStoreType = keyStoreType;
	}

	/// True when neither store is configured — callers should use the JVM
	/// default SSL context.
	@JsonIgnore
	public boolean isEmpty() {
		return isBlank(trustStore) && isBlank(keyStore);
	}

	/// Build an [SSLContext] from the configured stores, or return `null`
	/// when [#isEmpty()] — the caller then uses the JVM default. A configured
	/// half that fails to load throws rather than silently downgrading to
	/// default trust.
	public SSLContext buildSslContext() throws Exception {
		if (isEmpty()) {
			return null;
		}

		KeyManagerFactory kmf = null;
		if (!isBlank(keyStore)) {
			KeyStore ks = loadStore(keyStore, keyStoreType, keyStorePassword);
			kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(ks, chars(keyStorePassword));
		}

		TrustManagerFactory tmf = null;
		if (!isBlank(trustStore)) {
			KeyStore ts = loadStore(trustStore, trustStoreType, trustStorePassword);
			tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(ts);
		}

		SSLContext context = SSLContext.getInstance("TLS");
		context.init(kmf == null ? null : kmf.getKeyManagers(),
				tmf == null ? null : tmf.getTrustManagers(), null);
		return context;
	}

	private static KeyStore loadStore(String path, String type, String password) throws Exception {
		Path p = Paths.get(path);
		KeyStore store = KeyStore.getInstance(isBlank(type) ? "PKCS12" : type);
		try (InputStream in = Files.newInputStream(p)) {
			store.load(in, chars(password));
		}
		return store;
	}

	private static char[] chars(String s) {
		return (s == null) ? new char[0] : s.toCharArray();
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}
}
