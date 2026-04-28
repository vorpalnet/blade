package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Generic HMAC request signing. Covers the common webhook / API
/// signature pattern — GitHub, Shopify, Twilio, Stripe, and friends —
/// by signing a template of the request with a shared secret and
/// stamping the hex or base64 digest into a header.
///
/// Fields:
///
/// - `algorithm` — `"sha1"`, `"sha256"` (default), or `"sha512"`.
/// - `secret` — shared secret key; supports `${var}`.
/// - `header` — the header name to stamp (e.g. `"X-Signature"`,
///   `"X-Hub-Signature-256"`). Supports `${var}`.
/// - `payloadTemplate` — what to sign. Three request-level placeholders
///   are substituted *after* normal `${var}` resolution:
///   `${method}` (uppercase), `${url}` (resolved request URL), and
///   `${body}` (resolved request body; empty for GETs). Default:
///   `"${body}"` — matches most webhook signing.
/// - `encoding` — `"hex"` (default, lowercase) or `"base64"`.
/// - `prefix` — optional literal prepended to the encoded signature,
///   e.g. `"sha256="` for GitHub. Supports `${var}`.
///
/// Example: GitHub webhook signature verification (the outbound side).
///
/// ```json
/// "authentication": {
///   "type": "hmac",
///   "algorithm": "sha256",
///   "secret": "${GITHUB_WEBHOOK_SECRET}",
///   "header": "X-Hub-Signature-256",
///   "payloadTemplate": "${body}",
///   "encoding": "hex",
///   "prefix": "sha256="
/// }
/// ```
@JsonPropertyOrder({ "type", "algorithm", "secret", "header",
		"payloadTemplate", "encoding", "prefix" })
public class HmacAuthentication extends Authentication {
	private static final long serialVersionUID = 1L;

	private String algorithm = "sha256";
	private String secret;
	private String header = "X-Signature";
	private String payloadTemplate = "${body}";
	private String encoding = "hex";
	private String prefix = "";

	public HmacAuthentication() {
	}

	@JsonPropertyDescription("HMAC algorithm: sha1, sha256 (default), or sha512")
	public String getAlgorithm() {
		return algorithm;
	}

	public void setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
	}

	@JsonPropertyDescription("Shared secret; supports ${var}")
	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	@JsonPropertyDescription("Header name to stamp with the signature (e.g. X-Signature); supports ${var}")
	public String getHeader() {
		return header;
	}

	public void setHeader(String header) {
		this.header = header;
	}

	@JsonPropertyDescription("Template of what to sign; ${var} resolved first, then ${method}/${url}/${body} substituted. Default: ${body}")
	public String getPayloadTemplate() {
		return payloadTemplate;
	}

	public void setPayloadTemplate(String payloadTemplate) {
		this.payloadTemplate = payloadTemplate;
	}

	@JsonPropertyDescription("Output encoding: hex (default, lowercase) or base64")
	public String getEncoding() {
		return encoding;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	@JsonPropertyDescription("Optional prefix prepended to the encoded signature, e.g. \"sha256=\" for GitHub; supports ${var}")
	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		// Called when the RestConnector didn't supply request-level info.
		// Sign with an empty body; still functional for GET + static URL.
		applyTo(reqBuilder, ctx, new RequestSignature("GET", "", ""));
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx, RequestSignature req) {
		if (secret == null || header == null) return;
		Logger sipLogger = SettingsManager.getSipLogger();
		try {
			String jcaAlg = jcaName(algorithm);
			String resolvedSecret = ctx.resolve(secret);
			String resolvedHeader = ctx.resolve(header);
			String resolvedPrefix = (prefix == null) ? "" : ctx.resolve(prefix);
			String template = (payloadTemplate == null) ? "${body}" : payloadTemplate;

			String signingInput = ctx.resolve(template);
			signingInput = signingInput
					.replace("${method}", req.method())
					.replace("${url}", req.url())
					.replace("${body}", req.body());

			Mac mac = Mac.getInstance(jcaAlg);
			mac.init(new SecretKeySpec(
					resolvedSecret.getBytes(StandardCharsets.UTF_8), jcaAlg));
			byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));

			String encoded = "base64".equalsIgnoreCase(encoding)
					? Base64.getEncoder().encodeToString(signature)
					: HexBytes.toHex(signature);

			reqBuilder.header(resolvedHeader, resolvedPrefix + encoded);
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("HmacAuthentication failed: " + e.getMessage());
			}
		}
	}

	private static String jcaName(String alg) {
		if (alg == null) return "HmacSHA256";
		switch (alg.toLowerCase()) {
			case "sha1":   return "HmacSHA1";
			case "sha256": return "HmacSHA256";
			case "sha512": return "HmacSHA512";
			default:
				throw new IllegalArgumentException("unknown HMAC algorithm: " + alg);
		}
	}
}
