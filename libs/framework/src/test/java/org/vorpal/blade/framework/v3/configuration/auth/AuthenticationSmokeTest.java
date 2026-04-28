package org.vorpal.blade.framework.v3.configuration.auth;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Base64;
import java.util.List;

import org.vorpal.blade.framework.v3.FakeContext;

/// Smoke-test driver for the Authentication subtypes.
///
/// Covers the three static schemes (basic, bearer, apikey) and the two
/// request-signing schemes (hmac, aws-sigv4) by stamping headers on an
/// [HttpRequest.Builder] and inspecting the built request.
///
/// OAuth subtypes aren't tested here — they make a live HTTP call to
/// the token endpoint and would need a mock HTTP server.
public final class AuthenticationSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testBasicAuth();
		testBearerAuth();
		testApiKeyAuth();
		testHmacBasicStamping();
		testHmacDeterministic();
		testHmacPayloadSensitivity();
		testHmacBase64Encoding();
		testHmacPrefix();
		testHmacVarResolution();
		testAwsSigV4HeaderStructure();
		testAwsSigV4WithSessionToken();

		summary();
	}

	// ---- static schemes ----

	private static void testBasicAuth() {
		BasicAuthentication auth = new BasicAuthentication("alice", "secret");
		HttpRequest.Builder b = fresh();
		auth.applyTo(b, new FakeContext());
		String h = firstHeader(b, "Authorization");
		check("basic.startsWith", h != null && h.startsWith("Basic "));
		// Verify base64 payload decodes to expected credentials
		String b64 = h.substring("Basic ".length());
		String decoded = new String(Base64.getDecoder().decode(b64));
		check("basic.credentials", "alice:secret".equals(decoded));
	}

	private static void testBearerAuth() {
		BearerAuthentication auth = new BearerAuthentication("${apiKey}");
		HttpRequest.Builder b = fresh();
		auth.applyTo(b, new FakeContext().set("apiKey", "xyz-123"));
		check("bearer.stamp", "Bearer xyz-123".equals(firstHeader(b, "Authorization")));
	}

	private static void testApiKeyAuth() {
		ApiKeyAuthentication auth = new ApiKeyAuthentication("X-API-Key", "${token}");
		HttpRequest.Builder b = fresh();
		auth.applyTo(b, new FakeContext().set("token", "abc"));
		check("apikey.stamp", "abc".equals(firstHeader(b, "X-API-Key")));
	}

	// ---- HMAC ----

	private static void testHmacBasicStamping() {
		HmacAuthentication hmac = new HmacAuthentication();
		hmac.setAlgorithm("sha256");
		hmac.setSecret("shared-secret");
		hmac.setHeader("X-Signature");

		HttpRequest.Builder b = fresh();
		hmac.applyTo(b, new FakeContext(), sig("POST", "https://example.com", "payload"));
		String sig = firstHeader(b, "X-Signature");
		check("hmac.stamped", sig != null);
		check("hmac.sha256.hexLength", sig != null && sig.length() == 64);
		check("hmac.hexCharsOnly", sig != null && sig.matches("[0-9a-f]+"));
	}

	private static void testHmacDeterministic() {
		HmacAuthentication hmac = new HmacAuthentication();
		hmac.setSecret("k");
		hmac.setHeader("X-Sig");

		HttpRequest.Builder b1 = fresh();
		hmac.applyTo(b1, new FakeContext(), sig("POST", "https://a.example", "body"));
		String s1 = firstHeader(b1, "X-Sig");

		HttpRequest.Builder b2 = fresh();
		hmac.applyTo(b2, new FakeContext(), sig("POST", "https://a.example", "body"));
		String s2 = firstHeader(b2, "X-Sig");

		check("hmac.deterministic", s1 != null && s1.equals(s2));
	}

	private static void testHmacPayloadSensitivity() {
		HmacAuthentication hmac = new HmacAuthentication();
		hmac.setSecret("k");
		hmac.setHeader("X-Sig");

		HttpRequest.Builder b1 = fresh();
		hmac.applyTo(b1, new FakeContext(), sig("POST", "https://a.example", "payload-a"));
		String s1 = firstHeader(b1, "X-Sig");

		HttpRequest.Builder b2 = fresh();
		hmac.applyTo(b2, new FakeContext(), sig("POST", "https://a.example", "payload-b"));
		String s2 = firstHeader(b2, "X-Sig");

		check("hmac.bodyChanges.changeSig", !s1.equals(s2));

		// Key changes signature too
		HmacAuthentication hmac2 = new HmacAuthentication();
		hmac2.setSecret("different-key");
		hmac2.setHeader("X-Sig");
		HttpRequest.Builder b3 = fresh();
		hmac2.applyTo(b3, new FakeContext(), sig("POST", "https://a.example", "payload-a"));
		String s3 = firstHeader(b3, "X-Sig");
		check("hmac.keyChanges.changeSig", !s1.equals(s3));
	}

	private static void testHmacBase64Encoding() {
		HmacAuthentication hmac = new HmacAuthentication();
		hmac.setSecret("k");
		hmac.setHeader("X-Sig");
		hmac.setEncoding("base64");

		HttpRequest.Builder b = fresh();
		hmac.applyTo(b, new FakeContext(), sig("POST", "https://a.example", "body"));
		String sig = firstHeader(b, "X-Sig");
		check("hmac.base64.stamped", sig != null);
		// Base64 of SHA-256 output is 44 chars with padding
		check("hmac.base64.length", sig.length() == 44);
		try {
			Base64.getDecoder().decode(sig);
			passed++;
			System.out.println("PASS  hmac.base64.decodes");
		} catch (IllegalArgumentException e) {
			failed++;
			System.out.println("FAIL  hmac.base64.decodes (" + e.getMessage() + ")");
		}
	}

	private static void testHmacPrefix() {
		HmacAuthentication hmac = new HmacAuthentication();
		hmac.setSecret("k");
		hmac.setHeader("X-Hub-Signature-256");
		hmac.setPrefix("sha256=");

		HttpRequest.Builder b = fresh();
		hmac.applyTo(b, new FakeContext(), sig("POST", "https://a.example", "body"));
		String sig = firstHeader(b, "X-Hub-Signature-256");
		check("hmac.prefix.stamped", sig != null && sig.startsWith("sha256="));
		check("hmac.prefix.hexAfter", sig != null
				&& sig.substring("sha256=".length()).length() == 64);
	}

	private static void testHmacVarResolution() {
		HmacAuthentication hmac = new HmacAuthentication();
		hmac.setSecret("${SECRET}");
		hmac.setHeader("${HDR}");
		hmac.setPayloadTemplate("${method}:${body}");

		FakeContext ctx = new FakeContext().set("SECRET", "s1").set("HDR", "X-Foo");
		HttpRequest.Builder b = fresh();
		hmac.applyTo(b, ctx, sig("POST", "https://a.example", "bar"));

		String sig1 = firstHeader(b, "X-Foo");
		check("hmac.varResolved.header", sig1 != null);

		// Different secret → different signature
		ctx.set("SECRET", "s2");
		HttpRequest.Builder b2 = fresh();
		hmac.applyTo(b2, ctx, sig("POST", "https://a.example", "bar"));
		String sig2 = firstHeader(b2, "X-Foo");
		check("hmac.varSecret.changesSig", !sig1.equals(sig2));
	}

	// ---- AWS SigV4 ----

	private static void testAwsSigV4HeaderStructure() {
		AwsSigV4Authentication sig = new AwsSigV4Authentication();
		sig.setAccessKeyId("AKIDEXAMPLE");
		sig.setSecretAccessKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
		sig.setRegion("us-east-1");
		sig.setService("execute-api");

		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://apigw.us-east-1.amazonaws.com/prod/resource"));
		sig.applyTo(b, new FakeContext(),
				new Authentication.RequestSignature("POST",
						"https://apigw.us-east-1.amazonaws.com/prod/resource", "{\"a\":1}"));

		b.POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"));
		HttpRequest req = b.build();

		// Host is auto-set by the JDK HTTP client from the URI — it's
		// a restricted header that the builder can't set directly — so
		// we don't stamp it ourselves. The signature still covers the
		// Host value the JDK will produce.
		check("sigv4.amzDate.set",
				req.headers().firstValue("X-Amz-Date").isPresent());

		String auth = req.headers().firstValue("Authorization").orElse(null);
		check("sigv4.auth.set", auth != null);
		check("sigv4.auth.algorithm",
				auth != null && auth.startsWith("AWS4-HMAC-SHA256 "));
		check("sigv4.auth.credential",
				auth != null && auth.contains("Credential=AKIDEXAMPLE/"));
		check("sigv4.auth.scope",
				auth != null && auth.contains("/us-east-1/execute-api/aws4_request"));
		check("sigv4.auth.signedHeaders",
				auth != null && auth.contains("SignedHeaders=host;x-amz-date"));
		check("sigv4.auth.signature",
				auth != null && auth.matches(".*Signature=[0-9a-f]{64}$"));

		// Date is in amz format (YYYYMMDDTHHMMSSZ)
		String amzDate = req.headers().firstValue("X-Amz-Date").orElse("");
		check("sigv4.amzDate.format", amzDate.matches("\\d{8}T\\d{6}Z"));
	}

	private static void testAwsSigV4WithSessionToken() {
		AwsSigV4Authentication sig = new AwsSigV4Authentication();
		sig.setAccessKeyId("AKID");
		sig.setSecretAccessKey("secret");
		sig.setRegion("us-west-2");
		sig.setService("s3");
		sig.setSessionToken("session-token-xyz");

		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://s3.amazonaws.com/bucket/key"));
		sig.applyTo(b, new FakeContext(),
				new Authentication.RequestSignature("GET",
						"https://s3.amazonaws.com/bucket/key", ""));
		b.GET();
		HttpRequest req = b.build();

		check("sigv4.sessionToken.header",
				"session-token-xyz".equals(req.headers().firstValue("X-Amz-Security-Token").orElse(null)));

		String auth = req.headers().firstValue("Authorization").orElse("");
		check("sigv4.sessionToken.inSigned",
				auth.contains("SignedHeaders=host;x-amz-date;x-amz-security-token"));
	}

	// ---- helpers ----

	private static HttpRequest.Builder fresh() {
		return HttpRequest.newBuilder(URI.create("https://example.com/")).GET();
	}

	private static Authentication.RequestSignature sig(String method, String url, String body) {
		return new Authentication.RequestSignature(method, url, body);
	}

	private static String firstHeader(HttpRequest.Builder b, String name) {
		// HttpRequest.Builder doesn't expose headers directly; build to inspect.
		HttpRequest req = b.build();
		List<String> vals = req.headers().allValues(name);
		return vals.isEmpty() ? null : vals.get(0);
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}

	private static void summary() {
		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}
}
