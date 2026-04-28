package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// AWS Signature Version 4 request signing for calls to AWS services
/// (API Gateway execute-api, S3, Lambda function URLs, etc.). Hand-rolled;
/// no AWS SDK dependency.
///
/// Fields (all `${var}`-resolvable):
///
/// - `accessKeyId` — AWS access key identifier.
/// - `secretAccessKey` — AWS secret access key.
/// - `region` — AWS region, e.g. `"us-east-1"`.
/// - `service` — AWS service name, e.g. `"execute-api"`, `"s3"`,
///   `"lambda"`.
/// - `sessionToken` — optional; required when credentials come from
///   STS / an IAM role rather than long-lived user keys.
///
/// The signer stamps four headers on the request: `Host`, `X-Amz-Date`,
/// `Authorization`, and (when `sessionToken` is set) `X-Amz-Security-Token`.
/// Only these headers participate in the signature — other headers the
/// RestConnector or body template set are intentionally not signed, so
/// upstream modifications don't invalidate the signature. This is fine
/// for most AWS APIs, which only require the canonical subset. Callers
/// needing additional signed headers will need a dedicated subtype.
///
/// ## Simplifications vs. the full SigV4 spec
///
/// - **Path encoding**: the request path is passed through verbatim.
///   Clean, single-segment paths work; paths with reserved characters
///   may need pre-encoding. S3's double-encoding quirk is not supported.
/// - **Query-string canonicalization**: parameters are sorted by name;
///   values are not re-encoded. Clean query strings work; queries with
///   unusual characters may need pre-encoding.
/// - **Body hash**: SHA-256 of the exact bytes the RestConnector
///   produces after template resolution. Must match the bytes actually
///   sent — RestConnector doesn't mutate the body between signing and
///   send, so this holds.
///
/// Example: call an API Gateway endpoint in `us-east-1`:
///
/// ```json
/// "authentication": {
///   "type": "aws-sigv4",
///   "accessKeyId": "${AWS_ACCESS_KEY_ID}",
///   "secretAccessKey": "${AWS_SECRET_ACCESS_KEY}",
///   "region": "us-east-1",
///   "service": "execute-api"
/// }
/// ```
@JsonPropertyOrder({ "type", "accessKeyId", "secretAccessKey", "region",
		"service", "sessionToken" })
public class AwsSigV4Authentication extends Authentication {
	private static final long serialVersionUID = 1L;

	private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter
			.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter AMZ_DAY = DateTimeFormatter
			.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

	private String accessKeyId;
	private String secretAccessKey;
	private String region;
	private String service;
	private String sessionToken;

	public AwsSigV4Authentication() {
	}

	@JsonPropertyDescription("AWS access key identifier; supports ${var}")
	public String getAccessKeyId() {
		return accessKeyId;
	}

	public void setAccessKeyId(String accessKeyId) {
		this.accessKeyId = accessKeyId;
	}

	@JsonPropertyDescription("AWS secret access key; supports ${var}")
	public String getSecretAccessKey() {
		return secretAccessKey;
	}

	public void setSecretAccessKey(String secretAccessKey) {
		this.secretAccessKey = secretAccessKey;
	}

	@JsonPropertyDescription("AWS region, e.g. us-east-1; supports ${var}")
	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	@JsonPropertyDescription("AWS service name, e.g. execute-api, s3, lambda; supports ${var}")
	public String getService() {
		return service;
	}

	public void setService(String service) {
		this.service = service;
	}

	@JsonPropertyDescription("Optional STS session token (for temporary credentials); supports ${var}")
	public String getSessionToken() {
		return sessionToken;
	}

	public void setSessionToken(String sessionToken) {
		this.sessionToken = sessionToken;
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx) {
		// Without request details we can't sign a canonical request.
		applyTo(reqBuilder, ctx, new RequestSignature("GET", "", ""));
	}

	@Override
	public void applyTo(HttpRequest.Builder reqBuilder, Context ctx, RequestSignature req) {
		if (accessKeyId == null || secretAccessKey == null
				|| region == null || service == null) return;

		Logger sipLogger = SettingsManager.getSipLogger();
		try {
			String keyId    = ctx.resolve(accessKeyId);
			String secret   = ctx.resolve(secretAccessKey);
			String rgn      = ctx.resolve(region);
			String svc      = ctx.resolve(service);
			String token    = (sessionToken != null) ? ctx.resolve(sessionToken) : null;

			Instant now = Instant.now();
			String amzDate = AMZ_DATE.format(now);
			String dateStamp = AMZ_DAY.format(now);

			URI uri = URI.create(req.url());
			String hostname = uri.getHost();
			if (hostname == null) throw new IllegalArgumentException("URL has no host: " + req.url());
			// Match what Java's HTTP client will actually send as the Host
			// header — strip the port when it's the scheme's default.
			// Any mismatch between our signed Host and the sent Host voids
			// the signature.
			int port = uri.getPort();
			String host = hostname;
			if (port > 0 && !isDefaultPort(uri.getScheme(), port)) {
				host += ":" + port;
			}
			String path = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/" : uri.getPath();
			String query = (uri.getRawQuery() == null) ? "" : uri.getRawQuery();

			String payloadHash = sha256Hex(req.body());

			StringBuilder canonicalHeaders = new StringBuilder();
			canonicalHeaders.append("host:").append(host).append('\n');
			canonicalHeaders.append("x-amz-date:").append(amzDate).append('\n');
			if (token != null) {
				canonicalHeaders.append("x-amz-security-token:").append(token).append('\n');
			}

			String signedHeaders = (token != null)
					? "host;x-amz-date;x-amz-security-token"
					: "host;x-amz-date";

			String canonicalRequest = req.method() + "\n"
					+ path + "\n"
					+ canonicalQuery(query) + "\n"
					+ canonicalHeaders + "\n"
					+ signedHeaders + "\n"
					+ payloadHash;

			String credentialScope = dateStamp + "/" + rgn + "/" + svc + "/aws4_request";

			String stringToSign = "AWS4-HMAC-SHA256\n"
					+ amzDate + "\n"
					+ credentialScope + "\n"
					+ sha256Hex(canonicalRequest);

			byte[] kSecret  = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
			byte[] kDate    = hmacSha256(kSecret, dateStamp);
			byte[] kRegion  = hmacSha256(kDate, rgn);
			byte[] kService = hmacSha256(kRegion, svc);
			byte[] kSigning = hmacSha256(kService, "aws4_request");
			byte[] signature = hmacSha256(kSigning, stringToSign);
			String signatureHex = HexBytes.toHex(signature);

			String auth = "AWS4-HMAC-SHA256 "
					+ "Credential=" + keyId + "/" + credentialScope + ", "
					+ "SignedHeaders=" + signedHeaders + ", "
					+ "Signature=" + signatureHex;

			// Host is a restricted header on java.net.http.HttpRequest.Builder
			// — the JDK sets it automatically from the URI. We don't stamp
			// it, but it IS part of the canonical request above so the
			// signature covers what the JDK will actually send.
			reqBuilder.header("X-Amz-Date", amzDate);
			if (token != null) reqBuilder.header("X-Amz-Security-Token", token);
			reqBuilder.header("Authorization", auth);
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("AwsSigV4Authentication failed: " + e.getMessage());
			}
		}
	}

	private static String canonicalQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) return "";
		Map<String, String> sorted = new TreeMap<>();
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			String k = (eq >= 0) ? pair.substring(0, eq) : pair;
			String v = (eq >= 0) ? pair.substring(eq + 1) : "";
			sorted.put(k, v);
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : sorted.entrySet()) {
			if (sb.length() > 0) sb.append('&');
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	private static String sha256Hex(String data) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		return HexBytes.toHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] hmacSha256(byte[] key, String data) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isDefaultPort(String scheme, int port) {
		return ("https".equalsIgnoreCase(scheme) && port == 443)
				|| ("http".equalsIgnoreCase(scheme) && port == 80);
	}
}
