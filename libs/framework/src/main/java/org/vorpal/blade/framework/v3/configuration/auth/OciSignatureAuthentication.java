package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Oracle Cloud Infrastructure request signing, for calls to OCI's REST APIs
/// (Object Storage, Vault, anything else the tenancy exposes). Hand-rolled;
/// **no OCI SDK dependency**, for the same reason [AwsSigV4Authentication] is
/// hand-rolled.
///
/// That reason turned out to be load-bearing rather than tidiness. The OCI
/// Java SDK does not ship an HTTP client: its transport is pluggable and the
/// published options are Jersey builds, which drag a JAX-RS stack into an
/// application server that already has one and lose. Three headers and an RSA
/// signature do not need a transport layer, and this class is smaller than the
/// dependency it replaces.
///
/// Fields (all `${var}`-resolvable):
///
/// - `tenancyOcid`, `userOcid`, `fingerprint` — together they form the key
///   identifier OCI matches the signature against.
/// - `privateKey` — the API signing key, PEM, PKCS#8
///   (`-----BEGIN PRIVATE KEY-----`). Store it encrypted; `SettingsManager`
///   decrypts `{AES}` values on load like any other config secret.
///
/// ## What gets signed
///
/// OCI uses the HTTP Signatures scheme. The signing string is the newline-joined
/// list of `name: value` lines for the headers named in `headers`, where
/// `(request-target)` is the pseudo-header `<method> <path><?query>`.
///
/// Every request signs `(request-target)`, `host` and `date`. A request with a
/// body also signs `content-length`, `content-type` and `x-content-sha256`,
/// which is what stops a body being swapped under a valid signature. Those three
/// are stamped here as well as signed, so what is sent is what was signed.
///
/// ## The date header is the usual failure
///
/// OCI rejects a signature whose `date` is more than a few minutes from its own
/// clock, and the error says nothing about clocks. A signing failure that
/// appeared overnight and cleared by itself is almost always drift on the
/// signing host, not a bad key.
@JsonPropertyOrder({ "type", "tenancyOcid", "userOcid", "fingerprint", "privateKey" })
public class OciSignatureAuthentication extends Authentication {

	private static final long serialVersionUID = 1L;

	/// RFC 1123 in GMT, which is the only form OCI accepts for `date`.
	private static final DateTimeFormatter HTTP_DATE =
			DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

	private static final String SIGNED_NO_BODY = "(request-target) host date";
	private static final String SIGNED_WITH_BODY =
			"(request-target) host date content-length content-type x-content-sha256";

	private String tenancyOcid;
	private String userOcid;
	private String fingerprint;
	private String privateKey;

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		// A signature covers a specific method, path and body, so there is
		// nothing meaningful to sign without them. The three-argument form is
		// the real one; this exists only to satisfy the base class.
		applyTo(reqBuilder, ctx, new RequestSignature("GET", "", ""));
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx, RequestSignature req) {
		if (tenancyOcid == null || userOcid == null || fingerprint == null || privateKey == null) {
			return;
		}
		try {
			String keyId = ctx.resolve(tenancyOcid) + "/" + ctx.resolve(userOcid) + "/"
					+ ctx.resolve(fingerprint);

			URI uri = URI.create(req.url());
			String host = uri.getHost();
			if (host == null) {
				throw new IllegalArgumentException("URL has no host: " + req.url());
			}
			int port = uri.getPort();
			if (port > 0 && !(("https".equals(uri.getScheme()) && port == 443)
					|| ("http".equals(uri.getScheme()) && port == 80))) {
				host = host + ":" + port;
			}
			String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
			if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
				path = path + "?" + uri.getRawQuery();
			}

			String method = req.method().toLowerCase(Locale.US);
			String date = HTTP_DATE.format(Instant.now());
			String body = (req.body() == null) ? "" : req.body();
			boolean hasBody = "post".equals(method) || "put".equals(method) || "patch".equals(method);

			StringBuilder signing = new StringBuilder();
			signing.append("(request-target): ").append(method).append(' ').append(path);
			signing.append("\nhost: ").append(host);
			signing.append("\ndate: ").append(date);

			String headers = SIGNED_NO_BODY;
			String sha256 = null;
			if (hasBody) {
				byte[] raw = body.getBytes(StandardCharsets.UTF_8);
				sha256 = Base64.getEncoder().encodeToString(
						MessageDigest.getInstance("SHA-256").digest(raw));
				signing.append("\ncontent-length: ").append(raw.length);
				signing.append("\ncontent-type: application/json");
				signing.append("\nx-content-sha256: ").append(sha256);
				headers = SIGNED_WITH_BODY;
			}

			Signature rsa = Signature.getInstance("SHA256withRSA");
			rsa.initSign(parsePrivateKey(ctx.resolve(privateKey)));
			rsa.update(signing.toString().getBytes(StandardCharsets.UTF_8));
			String signature = Base64.getEncoder().encodeToString(rsa.sign());

			// Stamp what was signed, except the headers the client owns.
			//
			// `Host` is a restricted header: java.net.http refuses to set it and
			// throws. It is still signed, because OCI requires it in the signing
			// string, and the client sends the same value we computed as long as
			// the port rule below matches its own. Content-Length is the same
			// story: signed, never stamped. Anything the signer names but the
			// client sends differently voids the signature, and OCI reports that
			// only as a 401.
			reqBuilder.header("date", date);
			if (hasBody) {
				reqBuilder.header("x-content-sha256", sha256);
				reqBuilder.header("content-type", "application/json");
			}
			reqBuilder.header("Authorization", "Signature version=\"1\",keyId=\"" + keyId
					+ "\",algorithm=\"rsa-sha256\",headers=\"" + headers
					+ "\",signature=\"" + signature + "\"");

		} catch (Exception e) {
			// Never leak the key or the signing string; both are secrets.
			//
			// The container logger is null outside a WebLogic deployment, and an
			// error handler that throws hides the error it was meant to report.
			// That happened on the first run of this class: a signing failure
			// surfaced as a NullPointerException in the catch block.
			String message = "OciSignatureAuthentication: could not sign the request: " + e;
			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				sipLogger.severe(message);
			} else {
				java.util.logging.Logger.getLogger(getClass().getName())
						.log(java.util.logging.Level.SEVERE, message, e);
			}
		}
	}

	/// PEM PKCS#8 to a key.
	///
	/// Takes what lies **between** the BEGIN and END markers rather than deleting
	/// the markers and keeping the rest. Key files carry things either side of the
	/// block in practice: `oci setup keys` leaves a trailing `OCI_API_KEY` label,
	/// and operators add comments. Stripping only the markers leaves that text in
	/// the base64 and fails with "incorrect ending byte", which names neither the
	/// file nor the reason.
	///
	/// PKCS#1 (`BEGIN RSA PRIVATE KEY`) is refused rather than half-parsed: the two
	/// encodings look alike enough that a silent mis-parse would surface as an
	/// unexplained 401 much later.
	static PrivateKey parsePrivateKey(String pem) throws Exception {
		String text = pem.replace("\\n", "\n");
		if (text.contains("BEGIN RSA PRIVATE KEY")) {
			throw new IllegalArgumentException(
					"the signing key is PKCS#1; convert it with "
							+ "'openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem'");
		}
		int begin = text.indexOf("-----BEGIN");
		int beginEnd = (begin < 0) ? -1 : text.indexOf("-----", begin + 10);
		int end = text.indexOf("-----END");
		if (begin < 0 || beginEnd < 0 || end < 0 || end <= beginEnd) {
			throw new IllegalArgumentException(
					"the signing key is not PEM: no -----BEGIN/-----END block");
		}
		String base64 = text.substring(beginEnd + 5, end).replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(base64);
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
	}

	@JsonPropertyDescription("Tenancy OCID. With the user OCID and fingerprint it forms the key identifier OCI matches the signature against.")
	public String getTenancyOcid() {
		return tenancyOcid;
	}

	public void setTenancyOcid(String tenancyOcid) {
		this.tenancyOcid = tenancyOcid;
	}

	@JsonPropertyDescription("OCID of the user the API signing key belongs to.")
	public String getUserOcid() {
		return userOcid;
	}

	public void setUserOcid(String userOcid) {
		this.userOcid = userOcid;
	}

	@JsonPropertyDescription("Fingerprint of the API signing key, as shown in the OCI console.")
	public String getFingerprint() {
		return fingerprint;
	}

	public void setFingerprint(String fingerprint) {
		this.fingerprint = fingerprint;
	}

	@JsonPropertyDescription("API signing key, PEM PKCS#8 (-----BEGIN PRIVATE KEY-----). Store it encrypted: SettingsManager decrypts {AES} values on load.")
	public String getPrivateKey() {
		return privateKey;
	}

	public void setPrivateKey(String privateKey) {
		this.privateKey = privateKey;
	}
}
