package org.vorpal.blade.library.stir.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;

import java.net.URI;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Mints SHAKEN PASSporTs for tests.
///
/// Produces a fully-formed SIP `Identity` header value:
///
/// ```
/// <compact-JWS>;info=<x5u>;alg=<alg>;ppt=shaken
/// ```
///
/// The signing key alg is inferred from `PrivateKey` type. The verifier
/// only accepts ES256 in the spike; the RSA path exists so we can produce
/// a "wrong alg" fixture.
public final class TestSigner {

	private final PrivateKey privateKey;
	private final URI x5u;
	private final JWSAlgorithm alg;

	public TestSigner(PrivateKey privateKey, URI x5u) {
		this.privateKey = privateKey;
		this.x5u = x5u;
		if (privateKey instanceof ECPrivateKey) {
			this.alg = JWSAlgorithm.ES256;
		} else if (privateKey instanceof RSAPrivateKey) {
			this.alg = JWSAlgorithm.RS256;
		} else {
			throw new IllegalArgumentException("unsupported key type: "
					+ privateKey.getClass().getName());
		}
	}

	public String sign(String attest, String origTn, String destTn,
			long iat, String origid) throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("attest", attest);
		Map<String, Object> dest = new LinkedHashMap<>();
		dest.put("tn", Arrays.asList(destTn));
		payload.put("dest", dest);
		payload.put("iat", iat);
		Map<String, Object> orig = new LinkedHashMap<>();
		orig.put("tn", origTn);
		payload.put("orig", orig);
		payload.put("origid", origid);

		JWSHeader header = new JWSHeader.Builder(alg)
				.type(new JOSEObjectType("passport"))
				.x509CertURL(x5u)
				.customParam("ppt", "shaken")
				.build();

		JWSObject jws = new JWSObject(header, new Payload(payload));
		if (privateKey instanceof ECPrivateKey) {
			jws.sign(new ECDSASigner((ECPrivateKey) privateKey));
		} else {
			jws.sign(new RSASSASigner(privateKey));
		}

		return jws.serialize() + ";info=<" + x5u + ">;alg=" + alg.getName() + ";ppt=shaken";
	}

	/// Convenience for the happy path: A-attest, iat=now, random origid.
	public String signNow(String origTn, String destTn) throws Exception {
		return sign("A", origTn, destTn,
				System.currentTimeMillis() / 1000L,
				"test-" + System.nanoTime());
	}

	/// Replace the signature segment of a compact JWS inside an Identity
	/// header value, producing a value that parses but fails verification.
	public static String corruptSignature(String identityHeaderValue) {
		int semi = identityHeaderValue.indexOf(';');
		String jws = semi < 0 ? identityHeaderValue : identityHeaderValue.substring(0, semi);
		String tail = semi < 0 ? "" : identityHeaderValue.substring(semi);
		List<String> parts = Arrays.asList(jws.split("\\."));
		if (parts.size() != 3) throw new IllegalStateException("not a compact JWS");
		StringBuilder sigPart = new StringBuilder(parts.get(2));
		int idx = sigPart.length() / 2;
		char c = sigPart.charAt(idx);
		sigPart.setCharAt(idx, c == 'A' ? 'B' : 'A');
		return parts.get(0) + "." + parts.get(1) + "." + sigPart + tail;
	}
}
